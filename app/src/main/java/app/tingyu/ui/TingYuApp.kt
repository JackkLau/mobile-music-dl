package app.tingyu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tingyu.model.AppActions
import app.tingyu.model.AppUiState

@Composable
fun TingYuApp(state: AppUiState, actions: AppActions) {
    TingYuTheme {
        val snackbar = remember { SnackbarHostState() }
        val tabState = rememberSaveableStateHolder()
        val developerOpen = state.debugAvailable && state.debugSettings.enabled && state.developerVisible
        BackHandler(enabled = developerOpen) { actions.showDeveloperTools(false) }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it, withDismissAction = true)
                actions.dismissMessage()
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (state.playback.track != null) MiniPlayer(state, actions)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        val labels = listOf("解析", "音乐库", "下载", "我的")
                        val icons = listOf(Icons.Rounded.Link, Icons.Rounded.LibraryMusic, Icons.Rounded.Download, Icons.Rounded.PersonOutline)
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = state.tab == index, onClick = { actions.tab(index) },
                                icon = { Icon(icons[index], contentDescription = null) }, label = { Text(label) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (developerOpen) {
                    DeveloperToolsScreen(state, actions)
                } else tabState.SaveableStateProvider(state.tab) {
                    when (state.tab) {
                        1 -> LibraryScreen(state, actions)
                        2 -> DownloadsScreen(state, actions)
                        3 -> AccountScreen(state, actions)
                        else -> ParseScreen(state, actions)
                    }
                }
            }
        }
        if (state.playerVisible) PlayerDialog(state, actions)
        if (state.queueVisible && !state.playerVisible) QueueSheet(state, actions)
    }
}

@Composable
private fun MiniPlayer(state: AppUiState, actions: AppActions) {
    val player = state.playback
    val track = player.track ?: return
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开播放器") { actions.showPlayer(true) }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(track, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        player.isLoading -> "正在准备音频…"
                        player.error != null -> "播放失败，点此查看"
                        else -> track.artist.ifBlank { "哔哩哔哩" }
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = actions::togglePlayback, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (player.isLoading) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp)
                        Icon(if (player.isPlaying || player.isLoading) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (player.isLoading) "暂停缓冲" else if (player.isPlaying) "暂停" else "播放")
                    }
                }
                ActionIcon(Icons.Rounded.QueueMusic, "打开播放队列") { actions.showQueue(true) }
            }
            if (player.durationMs > 0) {
                LinearProgressIndicator(progress = { (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp))
            } else {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        }
    }
}
