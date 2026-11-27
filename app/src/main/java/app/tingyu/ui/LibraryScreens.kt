package app.tingyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tingyu.model.*
import app.tingyu.BuildConfig
import app.tingyu.R

@Composable
internal fun LibraryScreen(state: AppUiState, actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<LocalTrack?>(null) }
    LaunchedEffect(state.library, state.deletingLocalId) {
        if (deleteTarget != null && state.deletingLocalId == null && state.library.none { it.track.id == deleteTarget?.track?.id }) {
            deleteTarget = null
        }
    }
    val visible = remember(state.library, query) {
        state.library.filter { query.isBlank() || it.track.title.contains(query.trim(), true) || it.track.artist.contains(query.trim(), true) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("library_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("音乐库", if (state.library.isEmpty()) null else "${state.library.size} 首本地音乐") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::scanLocalMusic, enabled = !state.libraryWorking && state.deletingLocalId == null,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp)); Text("扫描已下载音乐")
                }
                OutlinedButton(onClick = actions::selectLocalFiles, enabled = !state.libraryWorking && state.deletingLocalId == null,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp)); Text("导入本地音频")
                }
                if (state.libraryWorking) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("library_progress"))
                state.libraryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.libraryNeedsPermission) Text("找回旧安装留下的文件需要音频访问权限，也可只选择要导入的文件。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.libraryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (state.library.isNotEmpty() || query.isNotEmpty()) item {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text("搜索歌名或歌手") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({ ActionIcon(Icons.Rounded.Close, "清空搜索") { query = "" } }) else null)
        }
        if (state.library.isEmpty()) {
            item { StatePanel(Icons.Rounded.LibraryMusic, "还没有本地音乐", actionLabel = "去解析链接", onAction = { actions.tab(AppTab.PARSE) }) }
        } else if (visible.isEmpty()) {
            item { StatePanel(Icons.Rounded.SearchOff, "没有找到匹配的音频", "换一个歌名或歌手关键词试试。", "清空搜索", { query = "" }) }
        } else {
            item {
                FilledTonalButton(onClick = { actions.play(visible.first().track, visible.map { it.track }) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp)); Text("播放${if (query.isNotBlank()) "搜索结果" else "全部"} · ${visible.size} 首")
                }
            }
            items(visible, key = { it.track.id }) { local ->
                TrackRow(local.track, saved = true, playing = state.playback.track?.id == local.track.id,
                    onPlay = { actions.play(local.track, visible.map { it.track }) }) {
                    ActionIcon(Icons.Rounded.Share, "分享 ${local.track.title}") { actions.shareMusic(local.track) }
                    ActionIcon(Icons.Rounded.DeleteOutline, "删除 ${local.track.title}", enabled = !state.libraryWorking && state.deletingLocalId == null) {
                        actions.dismissLocalDeleteError(); deleteTarget = local
                    }
                }
            }
        }
    }
    deleteTarget?.let { local ->
        val deleting = state.deletingLocalId == local.track.id
        AlertDialog(onDismissRequest = { if (!deleting) { deleteTarget = null; actions.dismissLocalDeleteError() } }, title = { Text("删除本地音频？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“${local.track.title}”将从手机中删除。" +
                    if (local.track.localOnly) "此操作无法撤销。" else "来源视频和 B 站收藏夹不会改变。")
                if (deleting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在删除，必要时请在系统窗口中确认。") }
                state.localDeleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { actions.deleteLocal(local) }, enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除音频") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null; actions.dismissLocalDeleteError() }, enabled = !deleting) { Text("保留") } })
    }
}

@Composable
internal fun DownloadsScreen(state: AppUiState, actions: AppActions) {
    val active = state.downloads.count { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SAVING) }
    LazyColumn(Modifier.fillMaxSize().testTag("downloads_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("下载", if (active > 0) "$active 个任务进行中" else null) }
        item { DownloadFolderCard(state, actions) }
        if (state.downloads.isEmpty()) {
            item { StatePanel(Icons.Rounded.Download, "还没有下载任务", actionLabel = "去解析链接", onAction = { actions.tab(AppTab.PARSE) }) }
        }
        items(state.downloads.reversed(), key = { it.track.id }) { task ->
            DownloadCard(task, actions)
        }
    }
}

@Composable
private fun DownloadFolderCard(state: AppUiState, actions: AppActions) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("下载位置", style = MaterialTheme.typography.titleMedium)
            }
            Text(state.downloadFolder.label, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = actions::selectDownloadFolder, enabled = !state.folderBusy) { Text("选择文件夹") }
                if (state.downloadFolder.tree != null) TextButton(onClick = actions::resetDownloadFolder, enabled = !state.folderBusy) { Text("恢复默认") }
            }
            state.folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DownloadCard(task: DownloadTask, actions: AppActions) {
    val active = task.status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.SAVING)
    val label = when (task.status) {
        DownloadStatus.QUEUED -> "等待下载"
        DownloadStatus.DOWNLOADING -> "正在下载"
        DownloadStatus.SAVING -> "正在整理音频"
        DownloadStatus.COMPLETED -> "下载完成"
        DownloadStatus.FAILED -> "下载失败"
        DownloadStatus.CANCELLED -> "已取消"
        DownloadStatus.INTERRUPTED -> "下载已中断"
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(task.track, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active) ActionIcon(Icons.Rounded.Close, "取消下载 ${task.track.title}") { actions.cancelDownload(task.track.id) }
                else if (task.status == DownloadStatus.COMPLETED) ActionIcon(Icons.Rounded.PlayArrow, "播放 ${task.track.title}") { actions.play(task.track, listOf(task.track)) }

            }
            if (task.status == DownloadStatus.DOWNLOADING) {
                if (task.totalBytes > 0) {
                    LinearProgressIndicator(progress = { (task.bytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatBytes(task.bytes)} / ${formatBytes(task.totalBytes)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${formatBytes(task.bytes)}", style = MaterialTheme.typography.labelMedium)
                }
            } else if (task.status == DownloadStatus.SAVING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            task.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (!active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.status != DownloadStatus.COMPLETED) {
                        OutlinedButton(onClick = { actions.retryDownload(task.track.id) },
                            modifier = Modifier.semantics { contentDescription = "重试下载 ${task.track.title}" }) { Text("重试下载") }
                    }
                    TextButton(onClick = { actions.removeDownload(task.track.id) },
                        modifier = Modifier.semantics { contentDescription = "移除记录 ${task.track.title}" }) { Text("移除记录") }
                }
                if (task.status == DownloadStatus.FAILED && task.track.source != MusicSource.BILIBILI) {
                    Row {
                        TextButton(onClick = { actions.loginForDownload(task.track.id) }) { Text("登录${task.track.source.label}") }
                        TextButton(onClick = { actions.openMusicPage(task.track) }) { Text("去官方歌曲页") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountScreen(state: AppUiState, actions: AppActions) {
    var confirmLogout by remember { mutableStateOf(false) }
    var guideVisible by rememberSaveable { mutableStateOf(false) }
    val account = state.session.account
    LazyColumn(Modifier.fillMaxSize().testTag("account_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageTitle("我的", trailing = {
                TextButton(onClick = { guideVisible = true }) {
                    Icon(Icons.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("使用说明")
                }
            })
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PersonOutline, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.name ?: "匿名听众", style = MaterialTheme.typography.titleLarge)
                            if (account != null) Text("哔哩哔哩账号", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (account == null) Button(onClick = actions::login, modifier = Modifier.fillMaxWidth()) { Text("登录哔哩哔哩") }
                    else OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(vertical = 8.dp).selectableGroup()) {
                    ModeRow("匿名模式", "访问公开视频", state.session.mode == SessionMode.ANONYMOUS) { actions.sessionMode(SessionMode.ANONYMOUS) }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    ModeRow("登录模式", "访问账号收藏夹", state.session.mode == SessionMode.ACCOUNT) {
                        if (account == null) actions.login() else actions.sessionMode(SessionMode.ACCOUNT)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                actions.chooseLinkSource(MusicSource.BILIBILI)
                actions.tab(AppTab.PARSE)
            }) {
                Icon(Icons.Rounded.PlaylistPlay, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入收藏夹链接")
            }
        }
        item(key = "download_folder") { DownloadFolderCard(state, actions) }
        item(key = "app_update") { AppUpdateCard() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandIcon(36.dp)
                if (state.debugAvailable) {
                    TextButton(onClick = actions::versionTap, modifier = Modifier.heightIn(min = 48.dp).testTag("app_version")) {
                        Text("${stringResource(R.string.app_name)} · ${BuildConfig.VERSION_NAME}")
                    }
                } else {
                    Text("${stringResource(R.string.app_name)} · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        if (state.debugAvailable) {
            item {
                OutlinedButton(onClick = { actions.showDeveloperTools(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("网络调试")
                }
            }
        }
        if (state.debugAvailable && state.debugSettings.enabled) {
            item {
                FilledTonalButton(onClick = { actions.showDeveloperTools(true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Build, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开发者工具")
                }
            }
        }
    }
    if (guideVisible) UsageGuide(onDismiss = { guideVisible = false }, onParse = {
        guideVisible = false
        actions.tab(AppTab.PARSE)
    })
    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出哔哩哔哩账号？") },
            text = { Text("当前登录会话和私密列表将被清除。已下载的音乐会保留，你仍然可以匿名使用。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; actions.logout() }) { Text("退出登录") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
    }
}

@Composable
internal fun ModeRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
