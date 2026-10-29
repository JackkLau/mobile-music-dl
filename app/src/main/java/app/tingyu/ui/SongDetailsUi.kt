package app.tingyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tingyu.lyrics.LrcParser
import app.tingyu.model.*

@Composable
internal fun SongDetailsDialog(state: AppUiState, actions: AppActions, track: Track) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artist by rememberSaveable(track.id) { mutableStateOf(track.artist) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = title != track.title || artist != track.artist
    val dismiss = { if (!state.musicSaving) { if (changed) confirmDiscard = true else actions.showMusicEditor(false) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("歌曲信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("只修改听屿中的显示信息，保留原音频和视频来源。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("歌名") }, singleLine = true,
                    enabled = !state.musicSaving, isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (title.isNotEmpty()) IconButton(onClick = { title = "" }, enabled = !state.musicSaving) { Icon(Icons.Rounded.Close, "清空歌名") } })
                OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("歌手") }, singleLine = true,
                    enabled = !state.musicSaving, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (artist.isNotEmpty()) IconButton(onClick = { artist = "" }, enabled = !state.musicSaving) { Icon(Icons.Rounded.Close, "清空歌手") } })
                if (changed) Text("修改歌名或歌手后，将清除这首歌已保存的歌词，可重新查找或导入。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.selectSongCover(track) }, enabled = !state.musicSaving, modifier = Modifier.weight(1f)) { Text("更换封面") }
                    TextButton(onClick = { actions.resetSongCover(track) }, enabled = !state.musicSaving && track.coverUrl.isNotBlank(), modifier = Modifier.weight(1f)) { Text("默认封面") }
                }
                if (track.sourceTitle.isNotBlank()) {
                    Text("${if (track.localOnly) "原始名称" else "原视频"}：${track.sourceTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (track.sourceArtist.isNotBlank()) Text("上传者：${track.sourceArtist}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.musicError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.musicSaving) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("song_saving"))
            }
        },
        confirmButton = { TextButton(onClick = { actions.saveMusicInfo(track, title, artist) }, enabled = !state.musicSaving && title.isNotBlank()) { Text("保存信息") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.musicSaving) { Text("取消") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("歌名和歌手的修改尚未保存。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; actions.showMusicEditor(false) }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}

@Composable
internal fun LyricsPanel(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier) {
    val document = state.lyrics
    val track = state.playback.track ?: return
    val lines = remember(document) { runCatching { LrcParser.parse(document?.syncedText.orEmpty()) }.getOrDefault(emptyList()) }
    val active = remember(lines, state.playback.positionMs) { LrcParser.activeIndex(lines, state.playback.positionMs) }
    val list = rememberLazyListState()
    var following by rememberSaveable(track.id, document) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(active, following, document) {
        if (following && active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    Column(modifier.testTag("lyrics_panel"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (document == null) "歌词" else listOf(document.source, if (document.instrumental) "纯音乐" else if (lines.isNotEmpty()) "滚动歌词" else "纯文本歌词").filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (lines.isNotEmpty()) TextButton(onClick = { following = !following }) { Text(if (following) "暂停跟随" else "回到当前") }
            if (document != null) ActionIcon(Icons.Rounded.DeleteOutline, "移除当前歌词", enabled = !state.musicSaving && !state.lyricsLoading) { confirmRemove = true }
        }
        if (document == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.lyricsLoading) "正在查找歌词…" else "还没有歌词", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("联网查找会将歌名、歌手和时长发送给 LRCLIB。也可导入本地 LRC／TXT。",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (document.instrumental) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("纯音乐，请欣赏", style = MaterialTheme.typography.titleLarge) }
        } else if (lines.isNotEmpty()) {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("lyrics_lines"),
                contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(line.text.ifBlank { "♪" }, modifier = Modifier.fillMaxWidth().semantics { selected = index == active },
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("plain_lyrics"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(document.plainText.lines()) { _, line -> Text(line, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        state.lyricsError?.let { Text(it, modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.lyricsLoading || state.musicSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { if (state.lyricsLoading) actions.cancelLyrics() else actions.findLyrics() }, enabled = !state.musicSaving) {
                Text(if (state.lyricsLoading) "取消查找" else "联网查找")
            }
            TextButton(onClick = { actions.importLyrics(track) }, enabled = !state.musicSaving && !state.lyricsLoading) { Text("导入歌词") }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除这首歌的歌词？") }, text = { Text("音频和原歌词文件会保留，可随时重新查找或导入。") },
        confirmButton = { TextButton(onClick = { actions.clearLyrics(); confirmRemove = false }) { Text("移除歌词") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("保留") } })
}
