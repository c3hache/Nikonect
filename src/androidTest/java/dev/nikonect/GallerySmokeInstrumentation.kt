package dev.nikonect

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import dev.nikonect.media.MediaLibrary
import dev.nikonect.ptp.ObjectInfo
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/** Only packaged in the androidTest APK. Test fixtures never touch existing user photos. */
class GallerySmokeInstrumentation : Instrumentation() {
    private var cleanup = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); cleanup = arguments?.getString("cleanup") == "true"; start() }
    override fun onStart() {
        val results = Bundle()
        try {
            val context = targetContext
            val preferences = context.getSharedPreferences("gallery_test_fixtures", 0)
            if (cleanup) {
                preferences.getStringSet("uris", emptySet())!!.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
                preferences.edit().clear().commit()
                results.putString("result", "Fixture cleanup passed")
            } else runBlocking {
                val library = MediaLibrary(context)
                val uris = mutableSetOf<String>()
                val jpegUris = mutableSetOf<String>()
                preferences.getStringSet("uris", emptySet())!!.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
                try {
                    for (number in 1..2) {
                        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        listOf(Color.rgb(34, 87, 125), Color.rgb(55, 138, 128), Color.rgb(227, 182, 67)).forEachIndexed { index, color ->
                            paint.color = color; canvas.drawRect(index * 300f, 0f, (index + 1) * 300f, 600f, paint)
                        }
                        paint.color = Color.WHITE; paint.textSize = 46f
                        canvas.drawText("Nikonect JPG test $number", 40f, 310f, paint)
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        bitmap.recycle()
                        val uri = library.save(ObjectInfo(0x3801, bytes.size.toLong(), "Nikonect_Test_$number.JPG")) { it.write(bytes) }
                        uris += uri.toString(); jpegUris += uri.toString()
                    }
                    val nef = byteArrayOf(73, 73, 42, 0, 0, 0, 0, 0)
                    val nefUri = library.save(ObjectInfo(0xB101, nef.size.toLong(), "Nikonect_Test_Raw.NEF")) { it.write(nef) }
                    uris += nefUri.toString()
                    val gallery = library.loadJpegs()
                    check(jpegUris.all { uri -> gallery.any { it.uri.toString() == uri } }) { "Imported JPG missing from gallery" }
                    check(gallery.none { it.name.endsWith(".nef", true) }) { "NEF leaked into JPG gallery" }
                    results.putString("result", "MediaStore integration passed: 2 JPG visible, NEF saved and excluded")
                } finally { preferences.edit().putStringSet("uris", uris).commit() }
            }
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) { results.putString("error", error.stackTraceToString()); finish(Activity.RESULT_CANCELED, results) }
    }
}
