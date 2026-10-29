package app.tingyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tingyu.R
import app.tingyu.model.AppActions
import app.tingyu.model.AppUiState
import app.tingyu.model.Track

@Composable
internal fun ParseScreen(state: AppUiState, actions: AppActions) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val result = state.result
    var selected by rememberSaveable(result?.favoriteId, result?.title) { mutableStateOf(emptyList<String>()) }
    var downloadConfirmation by remember { mutableStateOf<List<Track>?>(null) }
    val savedIds = remember(state.library) { state.library.map { it.track.id }.toSet() }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("parse_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandIcon()
                    Box(Modifier.weight(1f)) {
                        PageTitle(stringResource(R.string.app_name), "把喜欢的声音，留在身边。")
                    }
                }
                AssistChip(onClick = { actions.tab(3) }, label = { Text(if (state.session.isLoggedIn) "已登录" else "匿名模式") },
                    leadingIcon = { Icon(if (state.session.isLoggedIn) Icons.Rounded.VerifiedUser else Icons.Rounded.PersonOutline,
                        contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从一个链接开始", style = MaterialTheme.typography.titleLarge)
                    Text("粘贴视频或收藏夹链接，也可以从哔哩哔哩分享过来。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = state.input, onValueChange = actions::input, modifier = Modifier.fillMaxWidth(),
                        label = { Text("哔哩哔哩链接") }, placeholder = { Text("https://www.bilibili.com/video/…") },
                        minLines = 2, maxLines = 4, enabled = !state.parsing,
                        trailingIcon = if (state.input.isNotBlank()) ({
                            ActionIcon(Icons.Rounded.Close, "清空链接", enabled = !state.parsing) { actions.input("") }
                        }) else null,
                        shape = RoundedCornerShape(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { clipboard.getText()?.text?.let(actions::input) }, enabled = !state.parsing) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("粘贴")
                        }
                        Button(onClick = { keyboard?.hide(); actions.parse() }, enabled = state.input.isNotBlank() && !state.parsing,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            if (state.parsing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(if (state.parsing) "解析中" else "解析链接")
                        }
                    }
                }
            }
        }
        if (state.error != null) {
            item { StatePanel(Icons.Rounded.ErrorOutline, "暂时无法完成", state.error,
                if (result != null && result.nextPage != null) "重试加载" else "重新解析",
                if (result != null && result.nextPage != null) actions::loadMore else actions::parse, error = true) }
        }
        if (result == null && !state.parsing && state.error == null) {
            item { StatePanel(Icons.Rounded.Headphones, "先听，再决定留下", "解析后可以直接播放。喜欢的音频可以下载，离线也能听。") }
        }
        if (result != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge)
                    Text("${result.tracks.size} 个条目${if (result.nextPage != null) " · 还有更多" else ""}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result.tracks.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { actions.play(result.tracks.first(), result.tracks) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("播放列表")
                            }
                            OutlinedButton(onClick = {
                                selected = if (selected.size == result.tracks.size) emptyList() else result.tracks.map { it.id }
                            }, modifier = Modifier.weight(1f)) { Text(if (selected.size == result.tracks.size) "取消全选" else "全选已加载") }
                        }
                        if (selected.isNotEmpty()) {
                            Button(onClick = { downloadConfirmation = result.tracks.filter { it.id in selected } }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("下载所选 ${selected.size} 个条目")
                            }
                        }
                    }
                }
            }
            if (result.tracks.isEmpty()) {
                item { StatePanel(Icons.Rounded.PlaylistRemove, "这里还没有可播放的条目", "收藏夹可能为空，或其中的视频已失效。可以换一个链接试试。") }
            }
            items(result.tracks, key = { it.id }) { track ->
                TrackRow(track, track.id in savedIds, state.playback.track?.id == track.id,
                    onPlay = { actions.play(track, result.tracks) }, selected = track.id in selected,
                    onSelect = { selected = if (track.id in selected) selected - track.id else selected + track.id }) {
                    ActionIcon(if (track.id in savedIds) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        if (track.id in savedIds) "${track.title} 已下载" else "下载 ${track.title}", enabled = track.id !in savedIds) {
                        actions.download(listOf(track))
                    }
                }
            }
            if (result.nextPage != null) {
                item {
                    OutlinedButton(onClick = actions::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                        if (state.loadingMore) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (state.loadingMore) "正在加载" else "加载更多")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    downloadConfirmation?.let { tracks ->
        AlertDialog(onDismissRequest = { downloadConfirmation = null }, title = { Text("下载 ${tracks.size} 个条目？") },
            text = { Text("将依次保存到手机音乐目录。任务开始后可以在“下载”中查看进度或取消。") },
            confirmButton = { TextButton(onClick = { actions.download(tracks); downloadConfirmation = null; selected = emptyList() }) { Text("开始下载") } },
            dismissButton = { TextButton(onClick = { downloadConfirmation = null }) { Text("取消") } })
    }
}
