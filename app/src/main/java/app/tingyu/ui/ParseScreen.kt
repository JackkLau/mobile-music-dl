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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.tingyu.model.AppActions
import app.tingyu.model.AppUiState
import app.tingyu.model.MusicSource
import app.tingyu.model.Track

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParseScreen(state: AppUiState, actions: AppActions) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val result = state.result
    var selected by rememberSaveable(result?.favoriteId, result?.collectionUrl, result?.title) { mutableStateOf(emptyList<String>()) }
    var downloadConfirmation by remember { mutableStateOf<List<Track>?>(null) }
    var clipboardEmpty by remember(state.input, state.linkSource) { mutableStateOf(false) }
    var copiedCollection by remember(result?.collectionUrl) { mutableStateOf(false) }
    val savedIds = remember(state.library) { state.library.map { it.track.id }.toSet() }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("parse_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("添加音乐") }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MusicSource.entries.forEach { source ->
                            FilterChip(selected = state.linkSource == source, onClick = { actions.chooseLinkSource(source) },
                                label = { Text(source.label) })
                        }
                    }
                    OutlinedTextField(value = state.input, onValueChange = actions::input, modifier = Modifier.fillMaxWidth(),
                        label = { Text("${state.linkSource.label}链接") },
                        placeholder = { Text(if (state.linkSource == MusicSource.BILIBILI) "视频、合集或收藏夹分享文本" else "歌曲链接或分享文本") },
                        isError = clipboardEmpty,
                        supportingText = if (clipboardEmpty) ({ Text("剪贴板没有文字，请先从音乐平台复制链接。") }) else null,
                        minLines = 2, maxLines = 4, enabled = !state.parsing,
                        trailingIcon = if (state.input.isNotBlank()) ({
                            ActionIcon(Icons.Rounded.Close, "清空链接", enabled = !state.parsing) { actions.clearParse() }
                        }) else null,
                        shape = RoundedCornerShape(16.dp))
                    val needsPaste = state.input.isBlank()
                    Button(onClick = {
                        if (needsPaste) {
                            val pasted = try { clipboard.getText()?.text } catch (_: SecurityException) { null }
                            if (pasted.isNullOrBlank()) {
                                clipboardEmpty = true
                                return@Button
                            }
                            actions.input(pasted)
                        }
                        keyboard?.hide()
                        actions.parse()
                    }, enabled = !state.parsing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (state.parsing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(parseLinkIcon(),
                            contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.parsing) "解析中" else if (needsPaste) "粘贴并解析" else "解析链接")
                    }
                }
            }
        }
        if (state.error != null) {
            item { StatePanel(Icons.Rounded.ErrorOutline, "暂时无法完成", state.error,
                if (result != null && result.nextPage != null) "重试加载" else "重新解析",
                if (result != null && result.nextPage != null) actions::loadMore else actions::parse, error = true) }
        }
        if (result != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("解析结果", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = actions::clearParse) { Text("清除解析结果") }
                }
            }
        }
        val song = result?.tracks?.singleOrNull()?.takeIf { it.source != MusicSource.BILIBILI }
        if (song != null) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleLarge)
                        Text(listOf(song.artist, song.source.label).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium)
                        if (song.id in savedIds) Button(onClick = { actions.play(song, listOf(song)) }) { Text("播放已下载歌曲") }
                        else Button(onClick = { actions.download(listOf(song)) }) { Text("下载到手机") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { actions.shareMusic(song) }) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("分享歌曲")
                            }
                            TextButton(onClick = { actions.openMusicPage(song) }) { Text("去官方歌曲页") }
                            MusicAccountButton(song.source)
                        }
                    }
                }
            }
        } else if (result != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text("${result.tracks.size} 首${if (result.nextPage != null) " · 还有更多" else ""}",
                            modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        result.collectionUrl?.let { url ->
                            if (result.hasMoreInCollection) TextButton(onClick = {
                                actions.input(url)
                                actions.parse()
                            }) { Text("查看完整合集") }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(url))
                                copiedCollection = true
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (copiedCollection) "已复制合集链接" else "复制合集链接")
                            }
                        }
                    }
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
                item { StatePanel(Icons.Rounded.PlaylistRemove, "没有可播放的曲目", "请试试其他链接。") }
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
            text = { Text("保存到 ${state.downloadFolder.label}") },
            confirmButton = { TextButton(onClick = { actions.download(tracks); downloadConfirmation = null; selected = emptyList() }) { Text("开始下载") } },
            dismissButton = { TextButton(onClick = { downloadConfirmation = null }) { Text("取消") } })
    }
}
