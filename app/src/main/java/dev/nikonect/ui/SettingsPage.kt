package dev.nikonect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nikonect.camera.*

@Composable
internal fun SettingsPage(controller: CameraController, state: CameraState) {
    val pager = rememberPagerState { 4 }; val scope = rememberCoroutineScope()
    var reset by remember { mutableStateOf(false) }
    val s = state.settings
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pager.currentPage) {
            listOf("监看", "传输", "常规", "诊断").forEachIndexed { index, title -> Tab(pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(title) }) }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f)) { category ->
        val entries: List<@Composable () -> Unit> = when (category) {
            0 -> listOf(
                { SettingChoice("目标帧率", "上限取决于相机与 USB", s.targetFps, listOf(15 to "15 FPS", 24 to "24 FPS", 30 to "30 FPS")) { value -> controller.updateSettings { it.copy(targetFps = value) } } },
                { SettingChoice("预览解码尺寸", "较小尺寸降低手机解码负担", s.previewSize, listOf(640 to "640 · 省电", 960 to "960 · 流畅", 1280 to "1280 · 均衡", 1600 to "1600 · 清晰")) { value -> controller.updateSettings { it.copy(previewSize = value) } } },
                { SettingChoice("构图网格", "仅叠加在监看画面", s.grid, GridMode.entries.map { it to it.label }) { value -> controller.updateSettings { it.copy(grid = value) } } },
                { SettingToggle("亮度直方图", "根据预览图采样，每秒约 3 次", s.histogram) { value -> controller.updateSettings { it.copy(histogram = value) } } },
                { SettingToggle("监看优先", "监看时暂停传图与卡片扫描", s.prioritizeLive) { value -> controller.updateSettings { it.copy(prioritizeLive = value) } } },
                { SettingToggle("监看使用横屏", "其他页面恢复自动旋转", s.landscapeMonitor) { value -> controller.updateSettings { it.copy(landscapeMonitor = value) } } }
            )
            1 -> listOf(
                { SettingToggle("自动接收新照片", "仅处理连接后新增的对象", s.autoImport) { value -> controller.updateSettings { it.copy(autoImport = value) } } },
                { SettingToggle("同时传输 NEF", "默认关闭；JPG 会优先接收", s.importNef) { value -> controller.updateSettings { it.copy(importNef = value) } } },
                { SettingChoice("新照片检查间隔", "监看优先开启时不进行检查", s.pollSeconds, listOf(2 to "2 秒", 5 to "5 秒", 10 to "10 秒")) { value -> controller.updateSettings { it.copy(pollSeconds = value) } } },
                { InfoSetting("保存位置", "JPG：Pictures/Nikonect\nNEF：Download/Nikonect/NEF") },
                { InfoSetting("单独传输 NEF", "打开已接收或相机相册的 JPG，点传输对应 NEF。要求卡内保留 RAW+JPG 原片。") },
                { InfoSetting("监看和传图共享通道", "USB / Wi-Fi 均可接收；关闭监看优先可以边看边传，但整张照片下载会让画面停顿。") }
            )
            2 -> listOf(
                { SettingToggle("监看时屏幕常亮", "减少取景时自动锁屏", s.keepScreenOn) { value -> controller.updateSettings { it.copy(keepScreenOn = value) } } },
                { SettingToggle("沉浸式监看", "隐藏系统状态栏与导航栏", s.immersive) { value -> controller.updateSettings { it.copy(immersive = value) } } },
                { SettingToggle("切到后台时停止取景", "回到应用后可手动开始", s.pauseInBackground) { value -> controller.updateSettings { it.copy(pauseInBackground = value) } } },
                { SettingToggle("连接后自动开始取景", "完成相机连接和参数读取后启动", s.autoStartLive) { value -> controller.updateSettings { it.copy(autoStartLive = value) } } },
                { InfoSetting("参数与自动 ISO", "AUTO 优先显示相机感光度控制值；相机未提供时显示 —。监看顶部 + 展开工具，扳手打开设置；底部按钮切换主页面。") },
                { InfoSetting("无线连接", "在参数页点 Wi-Fi 图标。可选择手机连接相机热点，或相机加入手机热点后自动识别。") }
            )
            else -> listOf(
                { SettingToggle("实验性 Nikon 取景", "允许尝试相机未公布的取景操作", s.experimentalLive) { value -> controller.updateSettings { it.copy(experimentalLive = value) } } },
                { Column(Modifier.padding(12.dp)) { Text("运行诊断"); Text("导出帧率、USB/解码耗时及连接日志", style = MaterialTheme.typography.bodySmall); TextButton(onClick = controller::exportDiagnostics) { Text("导出到 Download/Nikonect") } } },
                { Column(Modifier.padding(12.dp)) { Text("最近状态", style = MaterialTheme.typography.titleSmall); Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 3) } },
                { Column(Modifier.padding(12.dp)) { Text("Nikonect 0.7.1 · 已接收 ${state.downloaded} 个文件", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { reset = true }) { Text("恢复默认设置") } } }
            )
        }
        SettingsGrid(entries)
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复默认设置？") }, text = { Text("恢复监看和传图选项，已保存的照片会保留。") }, confirmButton = { TextButton(onClick = { controller.updateSettings { AppSettings() }; reset = false }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

@Composable
private fun SettingsGrid(entries: List<@Composable () -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        if (maxWidth > 640.dp || maxHeight < 400.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val half = (entries.size + 1) / 2
            listOf(entries.take(half), entries.drop(half)).forEach { group -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { group.forEach { entry -> entry(); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)) } } }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { entries.forEach { entry -> entry(); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)) } }
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(settingIcon(title), null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> SettingChoice(title: String, detail: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(settingIcon(title), null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(6.dp)) { Text((options.firstOrNull { it.first == value }?.second ?: "") + " ▾", style = MaterialTheme.typography.labelMedium) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) { options.forEach { (option, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChange(option) }) } }
        }
    }
}

@Composable
private fun InfoSetting(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}


private fun settingIcon(title: String): UiIcon = when {
    title.contains("直方图") -> UiIcon.HISTOGRAM
    title.contains("网格") -> UiIcon.GRID
    title.contains("横屏") -> UiIcon.ROTATE
    title.contains("传") || title.contains("接收") || title.contains("NEF") -> UiIcon.DOWNLOAD
    title.contains("屏") || title.contains("监看") || title.contains("预览") || title.contains("帧率") -> UiIcon.MONITOR
    title.contains("取景") -> UiIcon.CAMERA
    else -> UiIcon.SETTINGS
}
