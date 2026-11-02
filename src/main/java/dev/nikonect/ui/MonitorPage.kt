package dev.nikonect.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nikonect.camera.*

@Composable
internal fun MonitorPage(controller: CameraController, state: CameraState, onNavigate: (Int) -> Unit) {
    val frame by controller.preview.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen && state.connected) SdkSettingsDialog(controller, state) { settingsOpen = false }
    var infoVisible by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var gridMenu by remember { mutableStateOf(false) }
    var propertyCode by rememberSaveable { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { kotlinx.coroutines.delay(2200); notice = "" } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val stale = state.lastParameterRead == 0L || now - state.lastParameterRead > 5000 || state.parameterWarning != null
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight
        frame?.let { Image(it.bitmap.asImageBitmap(), "相机实时画面", Modifier.fillMaxSize(), contentScale = if (state.settings.fillPreview) ContentScale.Crop else ContentScale.Fit) }
        val latestFrame by rememberUpdatedState(frame)
        if (state.live && state.settings.touchFocus) Box(Modifier.fillMaxSize().pointerInput(state.settings.fillPreview) {
            detectTapGestures { tap ->
                val f = latestFrame ?: return@detectTapGestures
                val aspect = f.bitmap.width.toFloat() / f.bitmap.height
                val w = if (state.settings.fillPreview) maxOf(size.width.toFloat(), size.height * aspect) else minOf(size.width.toFloat(), size.height * aspect)
                val h = w / aspect
                val x = (tap.x - (size.width - w) / 2) / w
                val y = (tap.y - (size.height - h) / 2) / h
                if (x !in 0f..1f || y !in 0f..1f) return@detectTapGestures
                val area = f.area
                if (area == null) notice = "当前帧没有对焦坐标"
                else {
                    notice = "请求对焦"
                    controller.focusAt((area.left + x * area.width).toInt().coerceIn(0, area.wholeW-1),
                        (area.top + y * area.height).toInt().coerceIn(0, area.wholeH-1))
                }
            }
        })
        if (infoVisible) frame?.let { current ->
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val aspect = current.bitmap.width.toFloat() / current.bitmap.height
                val imageWidth = if (state.settings.fillPreview) maxOf(size.width, size.height * aspect)
                    else minOf(size.width, size.height * aspect)
                val imageHeight = imageWidth / aspect
                current.focusBoxes.forEach { box ->
                    drawRect(if (box.focused) Color.Green else Color.White,
                        Offset((size.width - imageWidth) / 2 + box.x * imageWidth, (size.height - imageHeight) / 2 + box.y * imageHeight),
                        Size(box.width * imageWidth, box.height * imageHeight),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
        }
        GridOverlay(state.settings.grid, frame?.bitmap?.let { it.width.toFloat() / it.height } ?: 1.5f, state.settings.fillPreview, Modifier.fillMaxSize())
        if (frame == null) Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.live) "正在接收画面…" else "取景未开启", color = Color.White)
            if (!state.connected) TextButton(onClick = { onNavigate(1) }) { Text("前往参数页连接相机") }
            else Button(onClick = controller::startLiveView, enabled = !state.working) { Text("开始监看") }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Row(Modifier.height(42.dp).background(Color(0xDE162522), RoundedCornerShape(22.dp)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onNavigate(1) }) { AppIcon(UiIcon.UTURN, "返回参数页") }
                if (expanded) {
                    Box {
                        IconButton(onClick = { gridMenu = true }) { AppIcon(UiIcon.GRID, "选择构图网格") }
                        DropdownMenu(gridMenu, onDismissRequest = { gridMenu = false }) { GridMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, onClick = { gridMenu = false; controller.updateSettings { it.copy(grid = mode) } }) } }
                    }
                    IconToggleButton(checked = state.settings.histogram, onCheckedChange = { value -> controller.updateSettings { it.copy(histogram = value) } }) { AppIcon(UiIcon.HISTOGRAM, "亮度直方图") }
                    IconButton(onClick = { if (state.live) controller.stopLiveView() else controller.startLiveView() }, enabled = state.connected && !state.working) { AppIcon(if (state.live) UiIcon.STOP else UiIcon.PLAY, if (state.live) "停止监看" else "开始监看") }
                }
                if (expanded) Box {
                    IconButton(onClick = { more = true }) { AppIcon(UiIcon.WRENCH, "监看设置") }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("触摸对焦") }, trailingIcon = { Switch(state.settings.touchFocus, null) },
                            onClick = { controller.updateSettings { it.copy(touchFocus = !it.touchFocus) } })
                        DropdownMenuItem(text = { Text("全部相机参数") }, enabled = state.connected, onClick = { more = false; settingsOpen = true })
                        DropdownMenuItem(text = { Text(if (infoVisible) "隐藏画面参数" else "显示画面参数") }, onClick = { infoVisible = !infoVisible; more = false })
                        DropdownMenuItem(text = { Text(if (state.settings.landscapeMonitor) "恢复自动旋转" else "横屏监看") }, onClick = { more = false; controller.updateSettings { it.copy(landscapeMonitor = !it.landscapeMonitor) } })
                        DropdownMenuItem(text = { Text(if (state.settings.fillPreview) "显示完整画幅" else "填满画面（裁切边缘）") }, onClick = { more = false; controller.updateSettings { it.copy(fillPreview = !it.fillPreview) } })
                    }
                }
                IconButton(onClick = { expanded = !expanded; more = false; gridMenu = false }) {
                    AppIcon(if (expanded) UiIcon.REMOVE else UiIcon.ADD, if (expanded) "收起工具栏" else "展开工具栏")
                }
            }
        }
        if (frame != null && infoVisible && state.settings.histogram) Histogram(frame?.histogram ?: IntArray(64), Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp).width(if (wide) 160.dp else 115.dp).height(if (wide) 65.dp else 55.dp))
        if (notice.isNotEmpty()) Text(if (state.status.startsWith("操作失败")) state.status else notice,
            Modifier.align(Alignment.CenterStart).background(Color.Black.copy(alpha=.65f)).padding(6.dp), color = Color.White)
        if (state.connected) Column(Modifier.align(Alignment.BottomCenter).padding(6.dp).widthIn(max = 600.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (infoVisible) listOf(0x500D to "快门", 0x5007 to "光圈", 0x500F to "ISO", 0x5010 to "补偿").forEach { (code, label) ->
                    TextButton(onClick = { propertyCode = code }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            Text(state.property(code)?.display ?: "—", maxLines = 1, style = MaterialTheme.typography.titleSmall, color = if (stale) Color(0xFFFFBD75) else Color.White)
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = controller::capture, enabled = state.connected && !state.working, modifier = Modifier.padding(8.dp)) { AppIcon(UiIcon.CAMERA, "拍摄照片") }
            }
        }
        if (state.live && infoVisible) Text(if (state.movieMode) "视频 · LIVE" else "照片 · LIVE",
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = if (state.settings.histogram) 82.dp else 14.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    }
    propertyCode?.let { code ->
        val property = state.property(code)
        val label = when (code) { 0x500D -> "快门"; 0x5007 -> "光圈"; 0x500F -> "ISO"; else -> "曝光补偿" }
        AlertDialog(onDismissRequest = { propertyCode = null }, title = { Text(label) },
            text = { Column {
                PropertyControl(property, label, state.connected && !state.working) { controller.changeProperty(code, it) }
                if (code == 0x500F && !state.movieMode && CameraIso.automatic(state.properties) == true) Text("AUTO 为相机感光度控制值；— 表示相机未提供有效读数。请在相机关闭自动 ISO 后再调节设定值。", style = MaterialTheme.typography.bodySmall)
                Text(state.parameterWarning ?: state.status, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { propertyCode = null }) { Text("完成") } },
            dismissButton = { TextButton(onClick = controller::refreshProperties, enabled = state.connected && !state.working) { Text("刷新") } })
    }
}
@Composable
private fun GridOverlay(mode: GridMode, sourceAspect: Float, fill: Boolean, modifier: Modifier) {
    Canvas(modifier.clipToBounds()) {
        if (mode == GridMode.NONE) return@Canvas
        val width = if (fill) maxOf(size.width, size.height * sourceAspect) else minOf(size.width, size.height * sourceAspect)
        val height = width / sourceAspect
        val left = (size.width - width) / 2; val top = (size.height - height) / 2
        val color = Color.White.copy(alpha = .5f)
        if (mode == GridMode.CROSS) {
            val cx = size.width / 2; val cy = size.height / 2
            drawLine(color, Offset(cx - 22.dp.toPx(), cy), Offset(cx + 22.dp.toPx(), cy), 1.dp.toPx())
            drawLine(color, Offset(cx, cy - 22.dp.toPx()), Offset(cx, cy + 22.dp.toPx()), 1.dp.toPx())
        } else {
            val positions = when (mode) { GridMode.THIRDS -> listOf(1f / 3, 2f / 3); GridMode.QUARTERS -> listOf(.25f, .5f, .75f); else -> listOf(.382f, .618f) }
            positions.forEach { p ->
                drawLine(color, Offset(left + width * p, top), Offset(left + width * p, top + height), 1.dp.toPx())
                drawLine(color, Offset(left, top + height * p), Offset(left + width, top + height * p), 1.dp.toPx())
            }
        }
    }
}

@Composable
private fun Histogram(bins: IntArray, modifier: Modifier) {
    Column(modifier.background(Color.Black.copy(alpha = .6f)).padding(4.dp)) {
        Text("亮度", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val peak = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
            val width = size.width / bins.size.coerceAtLeast(1)
            bins.forEachIndexed { index, value ->
                val height = size.height * value.toFloat() / peak
                drawRect(Color(0xFFD8D8D8), Offset(index * width, size.height - height), Size((width - .5f).coerceAtLeast(1f), height))
            }
        }
    }
}



