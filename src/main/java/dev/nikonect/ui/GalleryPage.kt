package dev.nikonect.ui

import android.content.Intent
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nikonect.camera.*
import dev.nikonect.media.ImportedPhoto
import dev.nikonect.media.MediaLibrary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun GalleryPage(controller: CameraController, active: Boolean = true) {
    val allPhotos by controller.gallery.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val camera by controller.cameraListing.collectAsStateWithLifecycle()
    var folder by rememberSaveable { mutableStateOf("local") }

    var columns by rememberSaveable { mutableIntStateOf(3) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteSelected by remember { mutableStateOf<CameraEntry?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var targets by remember { mutableStateOf<List<ImportedPhoto>>(emptyList()) }
    var pendingOperation by remember { mutableStateOf<String?>(null) }
    var nefPhoto by remember { mutableStateOf<ImportedPhoto?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val photos = remember(allPhotos, folder) { allPhotos.filter { it.trashed == (folder == "trash") }.sortedByDescending { it.dateMillis } }
    val days = remember(photos) {
        val format = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
        photos.groupBy { format.format(Date(it.dateMillis)) }
    }
    val busy = localBusy || state.working || state.transferring
    fun changeFolder(value: String) { folder = value; selecting = false; checked = arrayListOf(); selected = null }
    fun toggle(photo: ImportedPhoto) { val id = photo.uri.toString(); checked = ArrayList(if (id in checked) checked - id else checked + id) }
    fun request(operation: String, list: List<ImportedPhoto>) { if (list.isNotEmpty()) { targets = list; pendingOperation = operation } }
    LaunchedEffect(active) { if (active) controller.refreshGallery() }
    LaunchedEffect(folder, state.connected) { if (folder == "camera" && state.connected) controller.refreshCameraFolder(false) }
    LaunchedEffect(photos, folder) { if (folder != "camera") checked = ArrayList(checked.filter { id -> photos.any { it.uri.toString() == id } }); if (selected != null && photos.none { it.uri.toString() == selected }) selected = null }
    BackHandler(active && (selecting || folder != "local") && selected == null && remoteSelected == null && pendingOperation == null && nefPhoto == null && !localBusy) {
        if (selecting) { selecting = false; checked = arrayListOf() }

        else changeFolder("local")
    }
    val itemCount = if (folder == "camera") camera.entries.size else photos.size
    val keys = remember(folder, photos, camera.entries) {
        if (folder == "camera") camera.entries.map { it.key } else photos.map { it.uri.toString() }
    }
    LaunchedEffect(state.connected) { if (!state.connected && folder == "camera") { remoteSelected = null; checked.clear(); selecting = false } }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        val sections = listOf("local" to "照片", "camera" to "相机", "trash" to "垃圾桶")
        TabRow(selectedTabIndex = sections.indexOfFirst { it.first == folder }.coerceAtLeast(0)) {
            sections.forEach { (key, label) -> Tab(selected = key == folder, enabled = !localBusy,
                onClick = { changeFolder(key) }, text = { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

            Text(if (selecting) "已选 ${checked.size} 张" else if (folder == "camera") "相机存储卡" else "${photos.size} 张照片",
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { selecting = !selecting; checked = arrayListOf() }, enabled = itemCount > 0 && !busy) { Text(if (selecting) "取消" else "多选") }
            IconButton(onClick = { if (folder == "camera") controller.refreshCameraFolder() else controller.refreshGallery() }, enabled = !busy && (folder != "camera" || state.connected)) { AppIcon(UiIcon.REFRESH, "刷新当前文件夹") }
        }
            if (selecting) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { checked = if (checked.size == keys.size) arrayListOf() else ArrayList(keys) }, enabled = !busy) { Text("全选") }
                val chosen = photos.filter { it.uri.toString() in checked }
                if (folder == "camera") IconButton(onClick = {
                    controller.downloadCameraBatch(camera.entries.filter { it.key in checked })
                }, enabled = !busy && checked.isNotEmpty()) { AppIcon(UiIcon.DOWNLOAD, "下载所选 JPG") }
                if (folder == "local") IconButton(onClick = { sharePhotos(context, chosen) }, enabled = chosen.isNotEmpty() && !busy) { AppIcon(UiIcon.SHARE, "分享所选照片") }
                if (folder == "trash") IconButton(onClick = { request("restore", chosen) }, enabled = chosen.isNotEmpty() && !busy) { AppIcon(UiIcon.RESTORE, "恢复所选照片") }
                if (folder != "camera") IconButton(onClick = { request(if (folder == "trash") "delete" else "trash", chosen) }, enabled = chosen.isNotEmpty() && !busy) { AppIcon(UiIcon.DELETE, if (folder == "trash") "彻底删除所选照片" else "将所选照片移入垃圾桶") }
            }
            if (folder == "camera") {

                Text(if (!state.connected) "请先连接相机，再点刷新" else camera.status, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                if (state.live) Text("刷新目录会停止监看，以便读取存储卡", style = MaterialTheme.typography.labelSmall)
            } else if (folder == "trash") Text("可单张或多选恢复、彻底删除。Android 11 及以上系统可能按到期时间清理垃圾桶。", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (busy || camera.loading && folder == "camera") LinearProgressIndicator(Modifier.fillMaxWidth())
            if (itemCount == 0) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when (folder) { "camera" -> if (camera.loading) "正在读取相机…" else "存储卡中没有 JPG"; "trash" -> "垃圾桶为空"; else -> "接收相机 JPG 后显示在这里" })
            } else {
                key(folder, if (folder == "camera") camera.path else null) {
                    val grid = rememberLazyGridState()
                    var drag by remember { mutableStateOf<Offset?>(null) }
                    var anchor by remember { mutableStateOf<String?>(null) }
                    var baseline by remember { mutableStateOf(emptySet<String>()) }
                    var adding by remember { mutableStateOf(true) }
                    val extend by rememberUpdatedState<(String) -> Unit> { target ->
                        val a = keys.indexOf(anchor)
                        val b = keys.indexOf(target)
                        if (a >= 0 && b >= 0) {
                            val range = keys.subList(minOf(a,b), maxOf(a,b) + 1)
                            checked = ArrayList(if (adding) baseline + range else baseline - range.toSet())
                        }
                    }
                    val start by rememberUpdatedState<(String) -> Boolean> { id ->
                        if (id !in keys) false else {
                            baseline = checked.toSet(); adding = id !in baseline; anchor = id; selecting = true
                            extend(id); true
                        }
                    }
                    LaunchedEffect(drag) {
                        val point = drag ?: return@LaunchedEffect
                        val edge = 48f
                        val speed = when {
                            point.y < edge -> -16f
                            point.y > grid.layoutInfo.viewportSize.height - edge -> 16f
                            else -> 0f
                        }
                        while (speed != 0f && grid.scrollBy(speed) != 0f) {
                            grid.keyAt(point)?.let(extend)
                            kotlinx.coroutines.delay(24)
                        }
                    }
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f).albumGestures(grid, !busy,
                        { delta -> columns = (columns + delta).coerceIn(1, 5) }, { start(it) }, { point ->
                            drag = point
                            if (point != null) grid.keyAt(point)?.let(extend)
                        }),
                        verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (folder == "camera") items(camera.entries, key = { it.key }) { entry ->
                            Box(Modifier.aspectRatio(1f)) {
                                CameraTile(controller, entry, busy, Modifier.fillMaxSize()) {
                                    if (selecting) checked = ArrayList(if (entry.key in checked) checked - entry.key else checked + entry.key)
                                    else remoteSelected = entry
                                }
                                if (selecting) Checkbox(entry.key in checked, null, Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha=.45f)))
                            }
                        } else days.forEach { (day, group) ->
                            item(key = day, span = { GridItemSpan(maxLineSpan) }) {
                                Text(day, Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                            }
                            items(group, key = { it.uri.toString() }) { photo ->
                                PhotoTile(photo, Modifier.aspectRatio(1f), selecting, photo.uri.toString() in checked,

                                    onClick = { if (!busy) { if (selecting) toggle(photo) else selected = photo.uri.toString() } })
                            }
                        }
                    }
                }
            }
        if (busy || state.status.startsWith("批量下载") || pendingOperation != null || state.status.startsWith("操作失败")) Text(state.status, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
    val selectedIndex = photos.indexOfFirst { it.uri.toString() == selected }
    if (selectedIndex >= 0) key(selected) { PhotoViewer(photos, selectedIndex,
        onSelect = { selected = photos[it].uri.toString() }, onClose = { selected = null }, busy = busy,
        onDelete = { request(if (folder == "trash") "delete" else "trash", listOf(photos[selectedIndex])) },
        onRestore = { request("restore", listOf(photos[selectedIndex])) }, onNef = { nefPhoto = photos[selectedIndex] }) }
    remoteSelected?.let { entry -> key(entry.token, entry.key) { CameraPhotoViewer(controller, entry, state,
        onClose = { remoteSelected = null }, onStep = { delta ->
            val index = camera.entries.indexOfFirst { it.key == entry.key }
            camera.entries.getOrNull(index + delta)?.let { remoteSelected = it }
        }) } }
    nefPhoto?.let { photo -> AlertDialog(onDismissRequest = { nefPhoto = null }, title = { Text("传输对应 NEF？") }, text = { Text("请连接拍摄此照片的相机，并保留卡内原 JPG 和同目录同名 NEF。${if (photo.source == null) "此照片没有相机来源记录，将按原文件名和字节大小匹配，请核对当前相机。" else "将核对相机来源和原片目录。"}此操作不改变自动 NEF 传输开关。") },
        confirmButton = { TextButton(enabled = state.connected && !busy, onClick = { nefPhoto = null; controller.downloadNefForPhoto(photo) }) { Text("传输 NEF") } }, dismissButton = { TextButton(onClick = { nefPhoto = null }) { Text("取消") } }) }
    pendingOperation?.let { operation ->
        val label = when (operation) { "trash" -> "移入垃圾桶"; "restore" -> "恢复"; else -> "彻底删除" }
        AlertDialog(onDismissRequest = { if (!localBusy) pendingOperation = null }, title = { Text("$label ${targets.size} 张照片？") },
            text = { Text(when (operation) { "delete" -> "将永久删除垃圾桶内选中的手机 JPG，无法恢复。"; "restore" -> "恢复后可在照片中查看。"; else -> "暂存到垃圾桶，之后可以恢复。Android 11 及以上系统可能按到期时间自动清理。" } + "相机原片及独立 NEF 文件将保留。") },
            confirmButton = { TextButton(enabled = !localBusy, onClick = {
                localBusy = true
                controller.changePhotos(targets, operation) { success -> localBusy = false; pendingOperation = null; if (success) { selected = null; checked = arrayListOf(); selecting = false } }
            }) { Text(if (localBusy) "处理中…" else label) } },
            dismissButton = { TextButton(enabled = !localBusy, onClick = { pendingOperation = null }) { Text("取消") } })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(photo: ImportedPhoto, modifier: Modifier, selecting: Boolean, checked: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.uri, photo.trashed) { value = MediaLibrary.thumbnail(context, photo.uri) }
    Box(modifier.background(Color.Black).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: AppIcon(UiIcon.GALLERY, photo.name)
            if (selecting) Checkbox(checked, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = .45f)))

    }
}

@Composable
private fun CameraTile(controller: CameraController, entry: CameraEntry, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, entry.key, entry.token, busy) { if (!entry.folder && !busy) value = controller.cameraImage(entry) }
    Card(onClick = onClick, enabled = !busy, modifier = modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), entry.info.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: AppIcon(if (entry.folder) UiIcon.FOLDER else UiIcon.GALLERY, null, Modifier.size(36.dp))
        }
        Text(entry.info.filename, Modifier.padding(6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoViewer(photos: List<ImportedPhoto>, index: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit, onNef: () -> Unit, busy: Boolean) {
    val photo = photos[index]
    val context = LocalContext.current
    val result by produceState<Pair<Boolean, Bitmap?>>(false to null, photo.uri, photo.trashed) { value = true to MediaLibrary.fullImage(context, photo.uri) }
    var scale by remember(photo.uri) { mutableFloatStateOf(1f) }
    var offset by remember(photo.uri) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = if (scale > 1f) offset + pan else Offset.Zero }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(photo.name, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(photo.dateMillis)) + " · %.1f MB".format(photo.bytes / 1048576.0), style = MaterialTheme.typography.labelSmall)
                        if (photo.trashed && photo.expiresAt > 0) Text("系统到期：" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(photo.expiresAt)), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = onClose) { AppIcon(UiIcon.CLOSE, "关闭照片") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(photo.uri, scale == 1f, busy) {
                    if (scale == 1f && !busy) {
                        var travel = 0f
                        detectHorizontalDragGestures(onDragStart = { travel = 0f }, onDragEnd = {
                            if (travel < -50.dp.toPx() && index < photos.lastIndex) onSelect(index + 1)
                            else if (travel > 50.dp.toPx() && index > 0) onSelect(index - 1)
                        }) { _, dx -> travel += dx }
                    }
                }.transformable(state = transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
                    val bitmap = result.second
                    if (bitmap != null) Image(bitmap.asImageBitmap(), photo.name, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                    else if (result.first) Text("无法读取图片，可以尝试恢复后查看") else CircularProgressIndicator()
                }
                if (!photo.trashed) TextButton(onClick = onNef, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { AppIcon(UiIcon.DOWNLOAD, null); Text("传输对应 NEF") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { onSelect(index - 1) }, enabled = index > 0 && !busy) { AppIcon(UiIcon.BEFORE, "上一张") }
                    if (!photo.trashed) IconButton(onClick = { sharePhotos(context, listOf(photo)) }, enabled = !busy) { AppIcon(UiIcon.SHARE, "分享照片") }
                    else IconButton(onClick = onRestore, enabled = !busy) { AppIcon(UiIcon.RESTORE, "恢复照片") }
                    IconButton(onClick = onDelete, enabled = !busy) { AppIcon(UiIcon.DELETE, if (photo.trashed) "彻底删除照片" else "移入垃圾桶") }
                    IconButton(onClick = { onSelect(index + 1) }, enabled = index + 1 < photos.size && !busy) { AppIcon(UiIcon.NEXT, "下一张") }
                }
            }
        }
    }
}

@Composable
private fun CameraPhotoViewer(controller: CameraController, entry: CameraEntry, state: CameraState, onClose: () -> Unit, onStep: (Int) -> Unit) {
    var full by remember { mutableStateOf(false) }
    var nefConfirm by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, entry.key, entry.token, full, state.working) { if (!state.working) value = controller.cameraImage(entry, full) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.info.filename, Modifier.weight(1f)); IconButton(onClick = onClose) { AppIcon(UiIcon.CLOSE, "关闭相机照片") } }
                Box(Modifier.weight(1f).fillMaxWidth().pointerInput(entry.key) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                        if (dx < -48.dp.toPx()) onStep(1) else if (dx > 48.dp.toPx()) onStep(-1)
                    }) { _, amount -> dx += amount }
                }, contentAlignment = Alignment.Center) { bitmap?.let { Image(it.asImageBitmap(), entry.info.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Text("没有可用预览，点查看原图或保存 JPG") }
                Text(if (full) "相机原图预览（临时缓存）" else "相机缩略图", style = MaterialTheme.typography.labelSmall)
                Text(state.status, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                if (state.working || state.transferring) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { full = true }, enabled = !state.working && !state.transferring && !full) { Text("查看原图") }
                    TextButton(onClick = { controller.downloadCameraJpeg(entry) }, enabled = state.connected && !state.working && !state.transferring) { Text("保存 JPG") }
                    TextButton(onClick = { nefConfirm = true }, enabled = state.connected && !state.working && !state.transferring) { Text("传输 NEF") }
                }
            }
        }
    }
    if (nefConfirm) AlertDialog(onDismissRequest = { nefConfirm = false }, title = { Text("传输同目录同名 NEF？") }, text = { Text("仅下载卡内已有的对应 NEF。若拍摄时未保存 RAW，无法从 JPG 生成 NEF。") }, confirmButton = { TextButton(onClick = { nefConfirm = false; controller.downloadCameraNef(entry) }) { Text("传输") } }, dismissButton = { TextButton(onClick = { nefConfirm = false }) { Text("取消") } })
}

private fun sharePhotos(context: Context, photos: List<ImportedPhoto>) {
    if (photos.isEmpty()) return
    val uris = ArrayList(photos.map { it.uri })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newUri(context.contentResolver, "Nikonect JPG", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { context.startActivity(Intent.createChooser(intent, "分享 ${uris.size} 张照片")) }
    catch (_: Exception) { Toast.makeText(context, "没有可接收照片的应用", Toast.LENGTH_SHORT).show() }
}
