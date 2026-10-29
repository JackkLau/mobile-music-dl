package app.tingyu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.tingyu.model.*

@Composable
internal fun PlayerDialog(state: AppUiState, actions: AppActions) {
    Dialog(onDismissRequest = { actions.showPlayer(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            val previousStatus = controller?.isAppearanceLightStatusBars
            val previousNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
                if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
            }
        }
        TingYuTheme(player = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PlayerContent(state, actions)
            }
            if (state.queueVisible) QueueSheet(state, actions)
        }
    }
}

@Composable
private fun PlayerContent(state: AppUiState, actions: AppActions) {
    val player = state.playback
    val track = player.track
    val local = state.library.any { it.track.id == track?.id }
    val pendingDownload = state.downloads.any { it.track.id == track?.id && it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SAVING) }
    var lyricsVisible by rememberSaveable(track?.id) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(TingYuColors.PlayerSurface, TingYuColors.Ink)))
        .statusBarsPadding().navigationBarsPadding()) {
        val compact = maxHeight < 500.dp
        val wideControls = compact && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.Rounded.KeyboardArrowDown, "收起播放器") { actions.showPlayer(false) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (lyricsVisible) track?.title.orEmpty() else "正在播放", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (lyricsVisible) track?.artist.orEmpty().ifBlank { "歌手待补充" }
                        else if (local) "已下载 · 可离线播放" else if (track?.localOnly == true) "本地文件不可用" else "在线播放",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (compact && track != null) PlayerTabs(lyricsVisible) { lyricsVisible = it }
                ActionIcon(Icons.Rounded.Edit, "编辑歌曲信息", enabled = track != null) { actions.showMusicEditor(true) }
            }
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("还没有正在播放的音频", style = MaterialTheme.typography.titleLarge)
                }
            } else {
                if (!compact) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        PlayerTabs(lyricsVisible) { lyricsVisible = it }
                    }
                }
                // The active content owns its scrolling. Transport controls below are never list items.
                if (lyricsVisible) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                        if (player.error != null) {
                            TextButton(onClick = { actions.play(track, player.queue.ifEmpty { listOf(track) }) }, modifier = Modifier.fillMaxWidth()) {
                                Text("播放中断 · 重新播放", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        LyricsPanel(state, actions, Modifier.weight(1f).fillMaxWidth(), compact = compact)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("player_list"),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item {
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val discSize = minOf(maxWidth, if (compact) 180.dp else 300.dp)
                                val ink = TingYuColors.PlayerAccent
                                Box(Modifier.size(discSize), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val radius = size.minDimension / 2
                                        drawCircle(ink.copy(alpha = 0.035f), radius)
                                        listOf(0.98f, 0.92f, 0.86f).forEach { factor -> drawCircle(ink.copy(alpha = 0.12f), radius * factor, style = Stroke(1.dp.toPx())) }
                                    }
                                    Cover(track, discSize * 0.70f)
                                }
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(track.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                                Text(track.artist.ifBlank { "歌手待补充" }, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                TextButton(onClick = { actions.showMusicEditor(true) }) { Text("编辑歌曲信息") }
                            }
                        }
                        if (player.error != null) {
                            item {
                                StatePanel(Icons.Rounded.ErrorOutline, "播放暂时中断", player.error, "重新播放",
                                    { actions.play(track, player.queue.ifEmpty { listOf(track) }) }, error = true)
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                ActionIcon(if (local) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                    if (local) "已下载" else if (track.localOnly) "本地音频" else if (pendingDownload) "正在下载" else "下载音频",
                                    enabled = !local && !pendingDownload && !track.localOnly) { actions.download(listOf(track)) }
                                Text(if (local) "已下载 · 可离线播放" else if (track.localOnly) "本地音频" else if (pendingDownload) "下载中" else "下载音频",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                PlayerControls(player, actions, compact, wideControls)
            }
        }
    }
    val editing = state.musicEditorTrack ?: track
    if (state.musicEditorVisible && editing != null) SongDetailsDialog(state, actions, editing)
}

@Composable
private fun PlayerTabs(lyricsVisible: Boolean, onSelect: (Boolean) -> Unit) {
    TextButton(onClick = { onSelect(false) }, modifier = Modifier.semantics { selected = !lyricsVisible },
        colors = ButtonDefaults.textButtonColors(containerColor = if (!lyricsVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) { Text("封面") }
    TextButton(onClick = { onSelect(true) }, modifier = Modifier.testTag("lyrics_tab").semantics { selected = lyricsVisible },
        colors = ButtonDefaults.textButtonColors(containerColor = if (lyricsVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) { Text("歌词") }
}

/** A measured sibling of the content area, so scrolling and long text cannot move transport controls. */
@Composable
private fun PlayerControls(player: PlaybackState, actions: AppActions, compact: Boolean, wide: Boolean) {
    val track = player.track ?: return
    val duration = player.durationMs.takeIf { it > 0 } ?: track.durationMs
    val canNext = player.queue.isNotEmpty() &&
        (player.mode == PlayMode.REPEAT_ALL || player.mode == PlayMode.SHUFFLE || player.index < player.queue.lastIndex)
    var scrubbing by remember(track.id) { mutableStateOf(false) }
    var sliderPosition by remember(track.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(player.positionMs, scrubbing) { if (!scrubbing) sliderPosition = player.positionMs.toFloat() }
    val progress: @Composable (Modifier) -> Unit = { modifier ->
        PlayerProgress(player, actions, duration, sliderPosition, scrubbing,
            onChange = { scrubbing = true; sliderPosition = it },
            onChangeFinished = { actions.seek(sliderPosition.toLong()); scrubbing = false }, modifier = modifier)
    }
    val transport: @Composable (Modifier) -> Unit = { modifier ->
        PlayerTransport(player, actions, canNext, compact, modifier)
    }
    Column(Modifier.fillMaxWidth().testTag("player_controls").background(TingYuColors.Ink)
        .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
        if (wide) {
            // Landscape has spare width but very little height: keep both parts on one bottom rail.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                progress(Modifier.weight(1f))
                transport(Modifier.width(320.dp))
            }
        } else {
            progress(Modifier.fillMaxWidth())
            transport(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PlayerProgress(player: PlaybackState, actions: AppActions, duration: Long, sliderPosition: Float,
    scrubbing: Boolean, onChange: (Float) -> Unit, onChangeFinished: () -> Unit, modifier: Modifier) {
    val maxPosition = duration.coerceAtLeast(1).toFloat()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.Replay10, "后退 10 秒", enabled = duration > 0) { actions.seek((player.positionMs - 10_000).coerceAtLeast(0)) }
            Slider(value = sliderPosition.coerceIn(0f, maxPosition), valueRange = 0f..maxPosition,
                enabled = duration > 0 && !player.isLoading,
                onValueChange = onChange, onValueChangeFinished = onChangeFinished,
                modifier = Modifier.weight(1f).semantics { contentDescription = "播放进度" })
            ActionIcon(Icons.Rounded.Forward10, "前进 10 秒", enabled = duration > 0) { actions.seek((player.positionMs + 10_000).coerceAtMost(duration)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (scrubbing) sliderPosition.toLong() else player.positionMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun PlayerTransport(player: PlaybackState, actions: AppActions, canNext: Boolean, compact: Boolean, modifier: Modifier) {
    val playSize = if (compact) 64.dp else 76.dp
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(modeIcon(player.mode), "切换播放模式，当前${modeLabel(player.mode)}", onClick = actions::cycleMode)
        ActionIcon(Icons.Rounded.SkipPrevious, "上一首", enabled = player.queue.isNotEmpty(), onClick = actions::previous)
        FilledIconButton(onClick = actions::togglePlayback, modifier = Modifier.size(playSize).testTag("player_toggle"), shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                if (player.isLoading) CircularProgressIndicator(Modifier.size(playSize - 14.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Icon(if (player.isPlaying || player.isLoading) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.isLoading) "暂停缓冲" else if (player.isPlaying) "暂停" else "播放", modifier = Modifier.size(40.dp))
            }
        }
        ActionIcon(Icons.Rounded.SkipNext, "下一首", enabled = canNext, onClick = actions::next)
        ActionIcon(Icons.Rounded.QueueMusic, "打开播放队列") { actions.showQueue(true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(state: AppUiState, actions: AppActions) {
    val player = state.playback
    ModalBottomSheet(onDismissRequest = { actions.showQueue(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放队列", style = MaterialTheme.typography.titleLarge)
                    Text("${player.queue.size} 首 · ${modeLabel(player.mode)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionIcon(Icons.Rounded.Close, "关闭播放队列") { actions.showQueue(false) }
            }
            if (player.queue.isEmpty()) {
                Text("从解析结果或音乐库选择一首音频。", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(player.queue, key = { index, track -> "${track.id}:$index" }) { index, track ->
                        TrackRow(track, saved = state.library.any { it.track.id == track.id }, playing = player.index == index,
                            onPlay = { actions.play(track, player.queue) }) {
                            if (player.index == index) Icon(Icons.Rounded.Equalizer, contentDescription = "当前曲目", modifier = Modifier.padding(12.dp).size(24.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun modeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.Rounded.PlaylistPlay
    PlayMode.REPEAT_ALL -> Icons.Rounded.Repeat
    PlayMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
    PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
}

private fun modeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENTIAL -> "顺序播放"
    PlayMode.REPEAT_ALL -> "列表循环"
    PlayMode.REPEAT_ONE -> "单曲循环"
    PlayMode.SHUFFLE -> "随机播放"
}
