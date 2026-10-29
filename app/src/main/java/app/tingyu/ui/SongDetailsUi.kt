package app.tingyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tingyu.lyrics.LrcParser
import app.tingyu.model.*
import coil.compose.AsyncImage
import java.util.Locale

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
                Text("只修改微乐中的显示信息，保留原音频和视频来源。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("歌名") }, singleLine = true,
                    enabled = !state.musicSaving, isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (title.isNotEmpty()) IconButton(onClick = { title = "" }, enabled = !state.musicSaving) { Icon(Icons.Rounded.Close, "清空歌名") } })
                OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("歌手") }, singleLine = true,
                    enabled = !state.musicSaving, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (artist.isNotEmpty()) IconButton(onClick = { artist = "" }, enabled = !state.musicSaving) { Icon(Icons.Rounded.Close, "清空歌手") } })
                if (changed) Text(if (state.coverSource.isNotBlank()) "修改歌名或歌手后，将清除已保存歌词和匹配的联网封面，可重新查找。手选图片会保留。"
                    else "修改歌名或歌手后，将清除这首歌已保存的歌词，可重新查找或导入。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.selectSongCover(track) }, enabled = !state.musicSaving, modifier = Modifier.weight(1f)) { Text("更换封面") }
                    TextButton(onClick = { actions.resetSongCover(track) }, enabled = !state.musicSaving && track.coverUrl.isNotBlank(), modifier = Modifier.weight(1f)) { Text("默认封面") }
                }
                Text("联网找封面会向网易云音乐、QQ 音乐查询歌名和歌手。选择后保存到本机，可离线使用。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { actions.findSongCover(track) }, enabled = !state.musicSaving && !changed && track.artist.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("联网找封面") }
                if (changed || track.artist.isBlank()) Text("请先保存完整歌名和歌手，再查找封面。", style = MaterialTheme.typography.bodySmall)
                if (state.coverSource.isNotBlank()) Text("封面来源：${state.coverSource}", style = MaterialTheme.typography.bodySmall)
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
    if (state.coverSearchTrack != null) ArtworkPickerDialog(state, actions)
}

@Composable
internal fun LyricsPanel(state: AppUiState, actions: AppActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val document = state.lyrics
    val track = state.playback.track ?: return
    val lines = remember(document) { runCatching { LrcParser.parse(document?.syncedText.orEmpty()) }.getOrDefault(emptyList()) }
    val active = remember(lines, state.playback.positionMs, document?.offsetMs) { LrcParser.activeIndex(lines, state.playback.positionMs, document?.offsetMs ?: 0L) }
    val list = rememberLazyListState()
    var following by rememberSaveable(track.id, document) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var confirmRemove by remember(track.id, document) { mutableStateOf(false) }
    var options by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(active, following, document) {
        if (following && active >= 0) list.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    Column(modifier.testTag("lyrics_panel"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                Text("联网查找会将歌名、歌手和时长发送给网易云音乐、LRCLIB。也可导入本地 LRC／TXT。",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (document.instrumental) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("纯音乐，请欣赏", style = MaterialTheme.typography.titleLarge) }
        } else if (lines.isNotEmpty()) {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("lyrics_lines"),
                contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(lines) { index, line ->
                    val duration = state.playback.durationMs.takeIf { it > 0 } ?: track.durationMs
                    Text(line.text.ifBlank { "♪" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = duration > 0, role = Role.Button, onClickLabel = "从此句播放") {
                            actions.seek(LrcParser.seekPosition(line, document.offsetMs, duration))
                            following = true
                        }.padding(vertical = 10.dp).semantics { selected = index == active },
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
        state.lyricsError?.let { Text(it, modifier = Modifier.heightIn(max = if (compact) 24.dp else 100.dp).verticalScroll(rememberScrollState()),
            maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.lyricsLoading || state.musicSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (state.lyricsLoading) actions.cancelLyrics() else actions.findLyrics() }, enabled = !state.musicSaving, modifier = Modifier.weight(1f)) {
                Text(if (state.lyricsLoading) "取消查找" else "联网查找")
            }
            TextButton(onClick = { actions.importLyrics(track) }, enabled = !state.musicSaving && !state.lyricsLoading, modifier = Modifier.weight(1f)) { Text("导入歌词") }
            ActionIcon(Icons.Rounded.Tune, "歌词选项") { options = true }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除这首歌的歌词？") }, text = { Text("音频和原歌词文件会保留，可随时重新查找或导入。") },
        confirmButton = { TextButton(onClick = { actions.clearLyrics(); confirmRemove = false }) { Text("移除歌词") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("保留") } })
    if (options) LyricsOptionsDialog(state, actions, track, hasTimedLyrics = lines.isNotEmpty(),
        following = following, onToggleFollowing = { following = !following },
        onRemove = { options = false; confirmRemove = true }, onDismiss = { options = false })
    if (state.lyricsChoiceTrack?.id == track.id) LyricVersionDialog(state, actions)
}

@Composable
private fun LyricsOptionsDialog(state: AppUiState, actions: AppActions, track: Track, hasTimedLyrics: Boolean,
    following: Boolean, onToggleFollowing: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val offset = state.lyrics?.offsetMs ?: 0L
    AlertDialog(onDismissRequest = onDismiss, title = { Text("歌词选项") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.lyricsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.lyrics?.source?.takeIf { it.isNotBlank() }?.let { Text("当前歌词来源：$it", style = MaterialTheme.typography.bodySmall) }
            Text("联网查找会向网易云音乐、LRCLIB发送歌名、歌手和时长。未自动匹配时会显示候选，可修改搜索词并核对不同来源的版本。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onDismiss(); actions.findLyricVersions() }, enabled = !state.musicSaving && !state.lyricsLoading,
                modifier = Modifier.fillMaxWidth()) { Text("选择歌词版本") }
            if (hasTimedLyrics) {
                TextButton(onClick = onToggleFollowing) { Text(if (following) "暂停跟随" else "回到当前") }
                Text("校准歌词时间", style = MaterialTheme.typography.titleMedium)
                Text(if (offset == 0L) "与原时间一致" else String.format(Locale.ROOT, "当前%s %.1f 秒", if (offset < 0) "提前" else "延后", kotlin.math.abs(offset) / 1000.0))
                Text("视频有前奏或剪辑时可微调，最多提前或延后 30 秒。点歌词可从该句播放。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.adjustLyricsOffset(track, -500) }, enabled = !state.musicSaving && !state.lyricsLoading && offset > -30000) { Text("歌词提前 0.5 秒") }
                TextButton(onClick = { actions.adjustLyricsOffset(track, 500) }, enabled = !state.musicSaving && !state.lyricsLoading && offset < 30000) { Text("歌词延后 0.5 秒") }
                TextButton(onClick = { actions.resetLyricsOffset(track) }, enabled = !state.musicSaving && !state.lyricsLoading && offset != 0L) { Text("重置时间") }
            }
            if (state.lyrics != null) TextButton(onClick = onRemove, enabled = !state.musicSaving && !state.lyricsLoading) { Text("移除当前歌词") }
            Text("到音乐平台核对", style = MaterialTheme.typography.titleMedium)
            Text("将在浏览器打开搜索页；可核对歌曲版本、歌手和专辑。豆瓣仅作资料参考，不自动抓取歌词或图片。", style = MaterialTheme.typography.bodySmall)
            val query = android.net.Uri.encode("${track.title} ${track.artist}".trim())
            listOf("网易云音乐" to "https://music.163.com/#/search/m/?s=$query&type=1",
                "QQ 音乐" to "https://y.qq.com/n/ryqq/search?w=$query&t=song",
                "豆瓣音乐资料" to "https://search.douban.com/music/subject_search?search_text=$query&cat=1003").forEach { (name, url) ->
                TextButton(onClick = { linkError = runCatching { uriHandler.openUri(url) }.isFailure }) { Text(name) }
            }
            if (linkError) Text("无法打开浏览器，请安装或启用浏览器后重试。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
private fun LyricVersionDialog(state: AppUiState, actions: AppActions) {
    val track = state.lyricsChoiceTrack ?: return
    val submittedTitle = state.lyricsQueryTitle ?: track.title
    val submittedArtist = state.lyricsQueryArtist ?: track.artist
    var title by rememberSaveable(track.id, submittedTitle) { mutableStateOf(submittedTitle) }
    var artist by rememberSaveable(track.id, submittedArtist) { mutableStateOf(submittedArtist) }
    val queryChanged = title.trim() != submittedTitle || artist.trim() != submittedArtist
    var selected by remember(track.id, state.lyricsCandidates, title, artist) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = actions::cancelLyrics, modifier = Modifier.testTag("lyric_version_dialog"), title = { Text("选择歌词版本") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${track.title} · ${track.artist}", style = MaterialTheme.typography.bodyMedium)
            Text("当前音频 ${formatTime(track.durationMs)}。请核对歌手、专辑和版本；选择后替换现有歌词，可在歌词选项中校准时间。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("搜索歌名") }, singleLine = true,
                enabled = !state.lyricsLoading, modifier = Modifier.fillMaxWidth().testTag("lyrics_search_title"),
                trailingIcon = { if (title.isNotEmpty()) IconButton(onClick = { title = "" }, enabled = !state.lyricsLoading) { Icon(Icons.Rounded.Close, "清空搜索歌名") } })
            OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("歌手（可选）") }, singleLine = true,
                enabled = !state.lyricsLoading, modifier = Modifier.fillMaxWidth().testTag("lyrics_search_artist"),
                trailingIcon = { if (artist.isNotEmpty()) IconButton(onClick = { artist = "" }, enabled = !state.lyricsLoading) { Icon(Icons.Rounded.Close, "清空搜索歌手") } })
            Text("搜索词只用于查找歌词，不会修改歌曲信息。找不到时可只填歌名。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { actions.searchLyricVersions(title.trim(), artist.trim()) },
                enabled = !state.lyricsLoading && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("搜索版本") }
            if (queryChanged) Text("搜索词已修改，请重新搜索后选择。", style = MaterialTheme.typography.bodySmall)
            if (state.lyricsLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取歌词版本…") }
            state.lyricsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.lyricsWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.lyricsCandidates.isNotEmpty()) Text("${state.lyricsCandidates.size} 个候选 · 请核对后选择", style = MaterialTheme.typography.labelMedium)
            state.lyricsCandidates.forEach { candidate ->
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (selected == candidate.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().clickable(enabled = !state.lyricsLoading && !queryChanged, role = Role.RadioButton) { selected = candidate.id }
                        .semantics { this.selected = selected == candidate.id }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${candidate.title} · ${candidate.artist}", style = MaterialTheme.typography.titleSmall)
                        if (candidate.album.isNotBlank()) Text(candidate.album, style = MaterialTheme.typography.bodyMedium)
                        Text("${candidate.source} · ${if (candidate.durationMs > 0) formatTime(candidate.durationMs) else "时长未知"}", style = MaterialTheme.typography.bodySmall)
                        if (track.durationMs > 0 && candidate.durationMs > 0 && kotlin.math.abs(track.durationMs - candidate.durationMs) > 2000)
                            Text("时长不同，请试听校准", style = MaterialTheme.typography.bodySmall)
                        Text(if (selected == candidate.id) "已选中" else "点按选择", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { state.lyricsCandidates.firstOrNull { it.id == selected }?.let(actions::useLyricVersion) },
            enabled = selected != null && !state.lyricsLoading && !queryChanged) { Text("使用此版本歌词") }
    }, dismissButton = { TextButton(onClick = actions::cancelLyrics) { Text(if (state.lyricsLoading) "取消查找" else "返回") } })
}

@Composable
private fun ArtworkPickerDialog(state: AppUiState, actions: AppActions) {
    val track = state.coverSearchTrack ?: return
    var selected by remember(track.id, state.coverCandidates) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!state.musicSaving) actions.cancelCoverSearch() }, title = { Text("选择歌曲封面") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${track.title} · ${track.artist}", style = MaterialTheme.typography.bodyMedium)
            Text("请核对专辑或艺人，选中后保存。艺人图片不代表这首歌的专辑封面。", style = MaterialTheme.typography.bodySmall)
            if (state.coverLoading || state.musicSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.coverLoading) Text("正在查找封面…")
            state.coverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.coverCandidates.forEach { candidate ->
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (selected == candidate.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.musicSaving, role = Role.RadioButton) { selected = candidate.id }
                        .semantics { this.selected = selected == candidate.id }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(model = candidate.imageUrl, contentDescription = "${candidate.kind}预览", contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(candidate.album.ifBlank { candidate.artist }, style = MaterialTheme.typography.titleSmall)
                            Text("${candidate.source} · ${candidate.kind}", style = MaterialTheme.typography.bodySmall)
                            Text(if (selected == candidate.id) "已选中" else "点按选择", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { state.coverCandidates.firstOrNull { it.id == selected }?.let(actions::useSongCover) },
            enabled = selected != null && !state.coverLoading && !state.musicSaving) { Text("使用所选封面") }
    }, dismissButton = { TextButton(onClick = actions::cancelCoverSearch, enabled = !state.musicSaving) { Text(if (state.coverLoading) "取消查找" else "返回") } })
}
