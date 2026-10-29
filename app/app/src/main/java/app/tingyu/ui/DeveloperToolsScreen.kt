package app.tingyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.debug.AudioUaMode
import app.tingyu.model.AppActions
import app.tingyu.model.AppUiState

/** A single expandable destination for present and future developer features. */
@Composable
internal fun DeveloperToolsScreen(state: AppUiState, actions: AppActions) {
    var chooseTrack by remember { mutableStateOf(false) }
    val settings = state.debugSettings
    val tracks = state.result?.tracks.orEmpty()
    val selected = tracks.firstOrNull { it.id == state.probeTrackId } ?: tracks.firstOrNull()
    DisposableEffect(Unit) { onDispose { actions.cancelUaComparison() } }
    LazyColumn(Modifier.fillMaxSize().testTag("developer_list"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回我的") { actions.showDeveloperTools(false) }
                Spacer(Modifier.width(8.dp))
                Text("开发者工具", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Text("真机验证结论：解析可使用桌面或移动 UA，音频下载需使用桌面 UA。测试设置仅在调试模式开启时生效。",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            DebugPanel("网络请求") {
                Text("解析 UA", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    UserAgentProfile.entries.forEach { ua ->
                        ModeRow("解析：${ua.label}", "", settings.parseUa == ua, !state.probeRunning) { actions.parseUa(ua) }
                    }
                }
                HorizontalDivider()
                Text("音频 UA", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    AudioUaMode.entries.forEach { mode ->
                        ModeRow("音频：${mode.label}", if (mode == AudioUaMode.MOBILE) "用于复现 403" else "",
                            settings.audioUa == mode, !state.probeRunning) { actions.audioUa(mode) }
                    }
                }
                Text("设置自动保存。正在下载的任务保持开始时的 UA；新任务采用新设置。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(settings.loggingEnabled, role = Role.Switch, onValueChange = actions::debugLogging),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("记录脱敏日志", Modifier.weight(1f))
                    Switch(checked = settings.loggingEnabled, onCheckedChange = null)
                }
                Text("日志不含 Cookie、账号信息或完整音频地址。关闭调试模式会停止日志并恢复默认设置。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            DebugPanel("同地址 UA 对照") {
                Text("按选定的解析 UA 取流一次，然后用桌面、移动 UA 请求同一地址，各读取最多 1 KB，不保存音频。",
                    style = MaterialTheme.typography.bodyMedium)
                if (selected == null) {
                    Text("先到解析页打开视频，再选择要测试的音轨。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { actions.tab(0) }) { Text("去解析链接") }
                } else {
                    Text("测试音轨：${selected.title}", style = MaterialTheme.typography.titleMedium)
                    if (tracks.size > 1) OutlinedButton(onClick = { chooseTrack = true }, enabled = !state.probeRunning) {
                        Text("选择测试音轨")
                    }
                }
                Button(onClick = actions::startUaComparison, enabled = selected != null && !state.probeRunning,
                    modifier = Modifier.fillMaxWidth()) { Text("开始同地址对照") }
                if (state.probeRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取音频样本…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onClick = actions::cancelUaComparison, modifier = Modifier.fillMaxWidth()) { Text("取消对照") }
                } else if (state.probeLines.isNotEmpty() && state.probeError == null) {
                    Text(if (state.probeLines.last() == "对照已取消") "对照已取消" else "对照已结束，请查看下方结果。",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                state.probeError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                state.probeLines.forEach { line -> Text(line, style = MaterialTheme.typography.labelSmall) }
            }
        }
        item {
            OutlinedButton(onClick = actions::disableDebugMode, modifier = Modifier.fillMaxWidth()) { Text("关闭调试模式") }
        }
    }
    if (chooseTrack) {
        AlertDialog(onDismissRequest = { chooseTrack = false }, title = { Text("选择测试音轨") }, text = {
            LazyColumn(Modifier.heightIn(max = 320.dp).selectableGroup()) {
                items(tracks, key = { it.id }) { track ->
                    ModeRow(track.title, "P${track.page}", track.id == selected?.id) {
                        actions.selectProbeTrack(track.id)
                        chooseTrack = false
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { chooseTrack = false }) { Text("关闭") } })
    }
}

@Composable
private fun DebugPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
