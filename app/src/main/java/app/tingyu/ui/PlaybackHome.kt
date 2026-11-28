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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tingyu.R
import app.tingyu.model.AppActions
import app.tingyu.model.AppTab
import app.tingyu.model.AppUiState

@Composable
internal fun PlaybackHome(state: AppUiState, actions: AppActions) {
    if (state.playback.track != null) {
        TingYuTheme(player = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PlayerContent(state, actions, embedded = true)
            }
        }
        return
    }
    var guide by rememberSaveable { mutableStateOf(false) }
    val openParse = { actions.tab(AppTab.PARSE) }
    val songs = remember(state.library) { state.library.map { it.track } }
    Column(Modifier.fillMaxSize()) {
        TingYuTheme(player = true) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BrandIcon(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                    TextButton(onClick = { guide = true }) { Text("使用说明") }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("playback_home"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (songs.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("听第一首歌", style = MaterialTheme.typography.headlineMedium)
                            Text("在音乐平台点「分享」，复制链接。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = openParse, modifier = Modifier.fillMaxWidth()) {
                                Icon(parseLinkIcon(), contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("从链接添加歌曲")
                            }
                            OutlinedButton(onClick = { actions.tab(AppTab.LIBRARY); actions.selectLocalFiles() },
                                modifier = Modifier.fillMaxWidth(), enabled = !state.libraryWorking) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("导入本地音频")
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本地音乐", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { actions.tab(AppTab.LIBRARY) }) { Text("查看全部") }
                    }
                }
                items(songs.take(5), key = { it.id }) { song ->
                    TrackRow(song, saved = true, playing = false, onPlay = { actions.play(song, songs) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    }
                }
                item { OutlinedButton(onClick = openParse, modifier = Modifier.fillMaxWidth()) { Text("从链接添加歌曲") } }
            }
        }
    }
    if (guide) UsageGuide(onDismiss = { guide = false }, onParse = { guide = false; openParse() })
}
