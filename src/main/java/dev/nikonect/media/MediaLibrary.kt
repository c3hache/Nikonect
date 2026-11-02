package dev.nikonect.media

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import dev.nikonect.ptp.ObjectInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.OutputStream

// Source identity is retained separately from the display name, which MediaStore may rename.
data class PhotoSource(val camera: String, val storage: Int, val parent: Int, val filename: String, val size: Long)
data class ImportedPhoto(val uri: Uri, val name: String, val dateMillis: Long, val bytes: Long,
    val folder: String = "已接收", val trashed: Boolean = false, val source: PhotoSource? = null, val expiresAt: Long = 0)
data class MediaChange(val completed: Int, val failed: Int)

class MediaLibrary(private val context: Context) {
    private val records = context.getSharedPreferences("nikonect_media_v1", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val resolver get() = context.contentResolver
    private fun record(uri: Uri) = runCatching { JSONObject(records.getString(uri.toString(), "{}")!!) }.getOrDefault(JSONObject())
    private fun source(json: JSONObject): PhotoSource? = if (json.has("camera")) PhotoSource(json.getString("camera"), json.getInt("storage"), json.getInt("parent"), json.getString("filename"), json.getLong("size")) else null

    suspend fun loadJpegs(): List<ImportedPhoto> = withContext(Dispatchers.IO) {
        val columns = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE, MediaStore.MediaColumns.RELATIVE_PATH)
        if (Build.VERSION.SDK_INT >= 30) { columns += MediaStore.MediaColumns.IS_TRASHED; columns += MediaStore.MediaColumns.DATE_EXPIRES }
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf("Pictures/Nikonect/%", "image/jpeg", context.packageName)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = if (Build.VERSION.SDK_INT >= 30) resolver.query(collection, columns.toTypedArray(), Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }, null) else resolver.query(collection, columns.toTypedArray(), selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
        val photos = mutableListOf<ImportedPhoto>()
        cursor?.use { c -> while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg")) continue
            val uri = ContentUris.withAppendedId(collection, c.getLong(0))
            val path = c.getString(4) ?: "Pictures/Nikonect/"
            val meta = record(uri)
            val trash = if (Build.VERSION.SDK_INT >= 30) c.getInt(5) == 1 else path == "Pictures/Nikonect/Trash/"
            val folder = if (trash) meta.optString("folder", "已接收") else if (path.contains("/Phone/")) "手机导入" else "已接收"
            photos += ImportedPhoto(uri, name, c.getLong(2) * 1000, c.getLong(3), folder, trash, source(meta), if (Build.VERSION.SDK_INT >= 30) c.getLong(6) * 1000 else 0)
        } }
        photos
    }

    /** All mutations recheck ownership and current trash state before changing a row. */
    suspend fun change(photos: List<ImportedPhoto>, operation: String): MediaChange = lock.withLock { withContext(Dispatchers.IO) {
        val owned = loadJpegs().associateBy { it.uri }
        var completed = 0; var failed = 0
        for (requested in photos.distinctBy { it.uri }) {
            ensureActive()
            val photo = owned[requested.uri]
            try {
                check(photo != null) { "照片不属于 Nikonect 相册" }
                val meta = record(photo.uri)
                val changed = when (operation) {
                    "trash" -> {
                        check(!photo.trashed)
                        meta.put("folder", photo.folder)
                        records.edit().putString(photo.uri.toString(), meta.toString()).commit().also { check(it) }
                        resolver.update(photo.uri, ContentValues().apply {
                            if (Build.VERSION.SDK_INT >= 30) put(MediaStore.MediaColumns.IS_TRASHED, 1)
                            else put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Nikonect/Trash/")
                        }, null, null)
                    }
                    "restore" -> {
                        check(photo.trashed)
                        resolver.update(photo.uri, ContentValues().apply {
                            if (Build.VERSION.SDK_INT >= 30) put(MediaStore.MediaColumns.IS_TRASHED, 0)
                            else put(MediaStore.MediaColumns.RELATIVE_PATH, if (photo.folder == "手机导入") "Pictures/Nikonect/Phone/" else "Pictures/Nikonect/")
                        }, null, null)
                    }
                    "delete" -> {
                        check(photo.trashed) { "永久删除仅限垃圾桶内照片" }
                        resolver.delete(photo.uri, null, null).also { if (it > 0) records.edit().remove(photo.uri.toString()).apply() }
                    }
                    else -> error("未知相册操作")
                }
                if (changed > 0) completed++ else failed++
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
        }
        MediaChange(completed, failed)
    } }

    // Compatibility entry point now moves photos to trash instead of deleting immediately.
    suspend fun delete(photos: List<ImportedPhoto>): Int = change(photos, "trash").completed

    suspend fun save(info: ObjectInfo, origin: PhotoSource? = null, download: suspend (OutputStream) -> Unit): Uri = withContext(Dispatchers.IO) {
        val name = info.filename.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(128)
        require(name.isNotBlank())
        val nef = name.endsWith(".nef", ignoreCase = true)
        val collection = if (nef) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (nef) "image/x-nikon-nef" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (nef) "Download/Nikonect/NEF" else "Pictures/Nikonect")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建手机文件")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                var received = 0L
                val counting = object : OutputStream() {
                    override fun write(value: Int) { stream.write(value); received++ }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) { stream.write(bytes, offset, count); received += count; check(received <= 512L * 1024 * 1024) { "文件超过 512MB" } }
                }
                download(counting)
                check(info.size == 0L || info.size == received) { "文件长度不一致，已清理未完成图片" }
            } ?: error("无法写入手机文件")
            if (!nef) {
                val meta = JSONObject().put("folder", "已接收")
                origin?.let { meta.put("camera", it.camera).put("storage", it.storage).put("parent", it.parent).put("filename", it.filename).put("size", it.size) }
                check(records.edit().putString(uri.toString(), meta.toString()).commit()) { "无法保存照片来源" }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
            uri
        } catch (e: Exception) { resolver.delete(uri, null, null); records.edit().remove(uri.toString()).apply(); throw e }
    }

    companion object {
        suspend fun thumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
            try { context.contentResolver.loadThumbnail(uri, Size(420, 320), CancellationSignal()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fullImage(context, uri) }
        }
        suspend fun fullImage(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
}