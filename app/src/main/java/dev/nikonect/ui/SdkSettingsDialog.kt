package dev.nikonect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nikonect.camera.CameraController
import dev.nikonect.camera.CameraState
import dev.nikonect.camera.sdkSettings
import dev.nikonect.ptp.DeviceSetting
import dev.nikonect.ptp.Ptp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun optionName(code: Int, value: String): String = when (code) {
    0xD1AC -> mapOf("1" to "QVGA", "2" to "VGA", "3" to "XGA")[value]
    0xD1BC -> mapOf("0" to "基本", "2" to "标准", "4" to "精细")[value]
    0xD054 -> mapOf("0" to "关闭", "1" to "开启")[value]
    else -> null
} ?: value

@Composable
internal fun SdkSettingsDialog(controller: CameraController, state: CameraState, close: () -> Unit) {
    val info = state.info ?: return
    var query by remember { mutableStateOf("") }
    var code by remember(info) { mutableStateOf<Int?>(null) }
    val modelIsZ50 = info.model.replace(" ", "").endsWith("Z50", true)
    val entries = remember(info) {
        info.properties.sorted().map { id ->
            val item = sdkSettings[id].takeIf { modelIsZ50 || id in 0x5000..0x5fff }
            Triple(id, item?.first ?: "设备属性", item?.second ?: "扩展参数")
        }
    }
    val selected = entries.firstOrNull { it.first == code }
    if (selected != null) {
        key(selected.first) { SettingEditor(controller, selected.first, selected.third, Ptp.PROP_SET in info.operations) { code = null } }
        return
    }
    val shown = entries.filter { (id, group, name) -> "${group} ${name} %04X".format(id).contains(query.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text("全部相机参数") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("搜索名称、分类或代码") }, singleLine = true)
            Text("机身报告 ${entries.size} 项 · 匹配 ${shown.size} 项", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.heightIn(max = 370.dp)) {
                items(shown, key = { it.first }) { (id, group, name) ->
                    Column(Modifier.fillMaxWidth().clickable { code = id }.padding(vertical = 7.dp)) {
                        Text(name)
                        Text("%s · %04X".format(group, id), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun SettingEditor(controller: CameraController, code: Int, name: String, canSet: Boolean, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf<DeviceSetting?>(null) }
    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(code, revision) {
        busy = true
        setting = null
        try {
            val read = controller.sdkSetting(code)
            setting = read
            draft = read.current
            message = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "读取失败：${e.message}" }
        finally { busy = false }
    }
    val p = setting
    val writable = p?.writable == true && canSet
    var choices by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) back() }, title = { Text(name) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                if (p != null) {
                    Text("机身当前值  " + optionName(code, p.current))
                    if (!writable) Text("当前只读")
                    else if (p.choices.isNotEmpty()) {
                        Box {
                            OutlinedButton(onClick = { choices = true }, enabled = !busy) { Text("选择  " + optionName(code, draft)) }
                            DropdownMenu(choices, { choices = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                                p.choices.forEach { value -> DropdownMenuItem(text = { Text(optionName(code, value)) }, onClick = { draft = value; choices = false }) }
                            }
                        }
                    } else {
                        OutlinedTextField(draft, { draft = it }, enabled = !busy, label = { Text(if (p.type == 0x4002) "十六进制字节" else "新值") })
                        if (p.limits.size == 3) Text("范围 ${p.limits[0]} 至 ${p.limits[1]}，步长 ${p.limits[2]}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (busy) LinearProgressIndicator()
                if (message.isNotEmpty()) Text(message)
                TextButton(onClick = { revision++ }, enabled = !busy) { Text("重新读取") }
            }
        }
    }, confirmButton = {
        Button(enabled = writable && !busy && runCatching { p.encode(draft) }.isSuccess, onClick = {
            val expected = p ?: return@Button
            busy = true
            scope.launch {
                try {
                    val result = controller.applySdkSetting(expected, draft)
                    message = if (result.current == draft) "相机已确认" else "机身回读为 " + optionName(code, result.current)
                    setting = result
                    draft = result.current
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "设置失败：${e.message}" }
                finally { busy = false }
            }
        }) { Text("写入相机") }
    }, dismissButton = { TextButton(onClick = back, enabled = !busy) { Text("返回列表") } })
}
