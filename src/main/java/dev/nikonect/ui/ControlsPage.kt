package dev.nikonect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nikonect.camera.CameraController
import dev.nikonect.camera.CameraState
import dev.nikonect.camera.movieMode
import dev.nikonect.camera.property
import dev.nikonect.ptp.CameraProperty

@Composable
internal fun PropertyControl(property: CameraProperty?, label: String, enabled: Boolean, compact: Boolean = false, onChange: (Int) -> Unit) {
    val editable = enabled && property?.writable == true && (property.options.isNotEmpty() || property.range != null)
    if (compact) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label + "  " + (property?.display ?: "—"), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            Row {
                TextButton(onClick = { onChange(-1) }, enabled = enabled && property?.canChange(-1) == true) { AppIcon(UiIcon.REMOVE, "减小参数") }
                TextButton(onClick = { onChange(1) }, enabled = enabled && property?.canChange(1) == true) { AppIcon(UiIcon.ADD, "增大参数") }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium)
        Text(property?.display ?: "—", style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { onChange(-1) }, enabled = editable && property?.canChange(-1) == true, contentPadding = PaddingValues(0.dp), modifier = Modifier.sizeIn(minWidth = 36.dp, minHeight = 32.dp)) { AppIcon(UiIcon.REMOVE, "减小参数") }
            TextButton(onClick = { onChange(1) }, enabled = editable && property?.canChange(1) == true, contentPadding = PaddingValues(0.dp), modifier = Modifier.sizeIn(minWidth = 36.dp, minHeight = 32.dp)) { AppIcon(UiIcon.ADD, "增大参数") }
        }
        if (!compact && property != null && !property.writable) Text("当前模式只读", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ControlsPage(controller: CameraController, state: CameraState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen && state.connected) SdkSettingsDialog(controller, state) { settingsOpen = false }
    var showStatus by remember { mutableStateOf(false) }
    if (showStatus) AlertDialog(onDismissRequest = { showStatus = false }, title = { Text("连接状态") }, text = { Text(state.status) }, confirmButton = { TextButton(onClick = { showStatus = false }) { Text("关闭") } }, dismissButton = { TextButton(onClick = controller::exportDiagnostics) { Text("导出诊断") } })
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val shortLayout = maxHeight < 400.dp
        val wide = maxWidth > 640.dp || shortLayout
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (shortLayout) 2.dp else 10.dp)) {
            ConnectionPanel(controller, state, wide)
            if (!wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.movieMode) "视频曝光" else "照片曝光", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = controller::refreshProperties, enabled = state.connected && !state.working) { AppIcon(UiIcon.REFRESH, "读取相机参数") }
            }
            val controls = listOf(0x500F to "ISO", 0x500D to "快门", 0x5007 to "光圈")
            if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                controls.forEach { (code, label) -> Card(Modifier.weight(1f).fillMaxHeight()) {
                    PropertyControl(state.property(code), label, state.connected && !state.working, compact = shortLayout) { controller.changeProperty(code, it) }
                } }
            } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                controls.forEach { (code, label) -> Card(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val p = state.property(code)
                        Column(Modifier.weight(1f)) { Text(label); Text(p?.display ?: "等待参数", style = MaterialTheme.typography.headlineSmall) }
                        val enabled = state.connected && !state.working && p?.writable == true && (p.options.isNotEmpty() || p.range != null)
                        OutlinedButton(onClick = { controller.changeProperty(code, -1) }, enabled = enabled && p?.canChange(-1) == true) { AppIcon(UiIcon.REMOVE, "减小参数") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { controller.changeProperty(code, 1) }, enabled = enabled && p?.canChange(1) == true) { AppIcon(UiIcon.ADD, "增大参数") }
                    }
                } }
            }
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.parameterWarning ?: state.status, modifier = Modifier.clickable { showStatus = true }, maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { settingsOpen = true }, enabled = state.connected) { Text("全部参数") }
            Button(onClick = { if (state.movieMode && !state.live) controller.startLiveView() else controller.capture() }, enabled = state.connected && !state.working, modifier = Modifier.fillMaxWidth()) { AppIcon(UiIcon.CAMERA, null); Spacer(Modifier.width(8.dp)); Text(if (state.movieMode && !state.live) "开启视频取景" else if (state.transferring) "正在传图…" else "拍摄照片") }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(controller: CameraController, state: CameraState, compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var wifiDialog by rememberSaveable { mutableStateOf(false) }
    if (wifiDialog) WirelessConnectionDialog(controller, state, onClose = { wifiDialog = false })
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(if (compact) 8.dp else 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (state.connected) "${state.info?.model} · ${if (state.movieMode) "视频" else "照片"}" else "连接相机", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (!compact) Text(if (state.connected) "参数自动同步 · 已接收 ${state.downloaded} 个文件" else "USB / Wi-Fi", style = MaterialTheme.typography.bodySmall)
            }
            if (state.connected) {
                if (compact) TextButton(onClick = controller::refreshProperties, enabled = !state.working) { Text("读参数") }
                OutlinedButton(onClick = controller::disconnect, enabled = !state.working) { AppIcon(UiIcon.DISCONNECT, "断开连接") }
            }
            else {
                IconButton(onClick = { wifiDialog = true }, enabled = !state.working) { AppIcon(UiIcon.WIFI, "连接相机 Wi-Fi") }
                IconButton(onClick = controller::refreshDevices) { AppIcon(UiIcon.REFRESH, "刷新 USB 设备") }
                Box {
                    Button(onClick = { if (state.devices.size == 1) controller.connect(state.devices.first()) else expanded = true }, enabled = !state.working && state.devices.isNotEmpty()) { Text("连接") }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        state.devices.forEach { device -> DropdownMenuItem(text = { Text(device.productName ?: device.deviceName) }, onClick = { expanded = false; controller.connect(device) }) }
                    }
                }
            }
        }
    }
}



