package dev.nikonect.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nikonect.camera.CameraController
import dev.nikonect.camera.CameraState

@Composable
internal fun WirelessConnectionDialog(controller: CameraController, state: CameraState, onClose: () -> Unit) {
    val context = LocalContext.current
    var hotspot by rememberSaveable { mutableStateOf(false) }
    var wtu by rememberSaveable { mutableStateOf(true) }
    val prompt by controller.pairingPrompt.collectAsState()
    var digits by remember(prompt) { mutableStateOf("") }
    var awaitingNetwork by rememberSaveable { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, hotspot, wtu, awaitingNetwork) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && awaitingNetwork) {
                awaitingNetwork = false
                controller.discoverWifi(hotspot, wtu)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.connected) { if (state.connected) onClose() }
    AlertDialog(onDismissRequest = { if (!state.working) onClose() },
        title = { Text("Wi-Fi 连接 · 实验性") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "相机热点", true to "手机热点").forEach { (mode, label) ->
                    FilterChip(selected = hotspot == mode, enabled = !state.working, onClick = {
                        hotspot = mode; wtu = mode
                    }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(wtu, onCheckedChange = { wtu = it }, enabled = !state.working)
                Text("机身使用「连接到计算机」/ 等待 WTU")
            }
            if (prompt != null && prompt != 0) {
                OutlinedTextField(digits, { digits = it.filter { c -> c in '0'..'9' }.take(prompt ?: 0) },
                    label = { Text("输入相机屏幕上的配对码") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
            } else if (prompt == 0) Text("请等相机显示配对完成，在机身按 OK，再点下方按钮。")
            if (hotspot) Text("开启手机热点（WPA2），在 Z50 的「连接到 PC → 网络设定 → 创建配置」中搜索并加入热点。在机身等待 WTU 时开始识别。", style = MaterialTheme.typography.bodySmall)
            else if (wtu) Text("在 Z50 的「连接到计算机」中建立直连网络。手机加入相机热点，保持机身配对页面打开。")
            else Text("Z50：设定菜单 → 连接至智能设备 → Wi-Fi 连接 → 建立 Wi-Fi 连接。手机连接相机显示的热点，选择保留无互联网连接，并退出 SnapBridge。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { awaitingNetwork = true; context.startActivity(Intent(if (hotspot) Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_WIFI_SETTINGS)) }, enabled = !state.working) { AppIcon(UiIcon.WIFI, null); Text(if (hotspot) "打开系统网络设置" else "打开 Wi-Fi 设置") }
            Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !state.working || prompt == 0 || prompt == digits.length,
            onClick = { if (prompt != null) controller.submitPairing(digits) else controller.discoverWifi(hotspot, wtu) }) {
                Text(when (prompt) { 0 -> "机身已确认，重新连接"; null -> if (state.working) "正在连接…" else "自动识别并连接"; else -> "核对配对码" })
            } },
        dismissButton = { TextButton(onClick = { if (state.working) controller.cancelWireless(); onClose() }) { Text(if (state.working) "取消连接" else "关闭") } }
    )
}
