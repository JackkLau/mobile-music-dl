package app.tingyu.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tingyu.BuildConfig
import app.tingyu.update.AppUpdateViewModel
import app.tingyu.update.UpdatePhase

private const val APK_MIME = "application/vnd.android.package-archive"

@Composable
internal fun AppUpdateCard(model: AppUpdateViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val release = state.release
    var requestedDigest by rememberSaveable { mutableStateOf<String?>(null) }
    var exportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME)) {
        exportUri = it
        if (it == null) requestedDigest = null
    }
    // Wait for a restored private cache before consuming a result from the system picker.
    LaunchedEffect(state.phase, exportUri) {
        val destination = exportUri ?: return@LaunchedEffect
        if (state.phase == UpdatePhase.CHECKING) return@LaunchedEffect
        val matches = state.phase == UpdatePhase.READY && release?.sha256 == requestedDigest
        exportUri = null
        requestedDigest = null
        if (matches) model.saveCopy(destination)
        else model.feedback("安装包已变更或缓存已清理，请重新下载")
    }

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            val subtitle = when (state.phase) {
                UpdatePhase.IDLE -> "当前版本 ${BuildConfig.VERSION_NAME}"
                UpdatePhase.CHECKING -> "正在检查最新正式版…"
                UpdatePhase.AVAILABLE -> when (release!!.compareToInstalled(BuildConfig.VERSION_NAME)) {
                    0 -> "已是最新版本 · ${release.version}"
                    1 -> "发现新版本 ${release.version} · 当前 ${BuildConfig.VERSION_NAME}"
                    else -> "最新正式版 ${release.version} · 当前 ${BuildConfig.VERSION_NAME}"
                }
                UpdatePhase.DOWNLOADING -> if (state.bytes == release!!.size) "正在校验安装包…"
                    else "正在下载 ${release.version} · ${formatBytes(state.bytes)} / ${formatBytes(release.size)}"
                UpdatePhase.READY -> if (state.canInstall) "${release!!.version} 安装包已就绪"
                    else "${release!!.version} 正式包已下载，与当前安装版本不兼容"
                UpdatePhase.SAVING -> "正在保存安装包…"
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { (state.bytes.toFloat() / release!!.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                }
                UpdatePhase.AVAILABLE -> {
                    FilledTonalButton(onClick = model::download) { Text("下载安装包 · ${formatBytes(release!!.size)}") }
                }
                UpdatePhase.READY -> {
                    FilledTonalButton(onClick = {
                        try {
                            requestedDigest = release!!.sha256
                            export.launch(release.fileName)
                        } catch (_: ActivityNotFoundException) {
                            model.feedback("设备没有可用的文件选择器")
                        }
                    }) { Text("保存安装包…") }
                }
                UpdatePhase.IDLE -> Unit
            }
            if (state.phase in setOf(UpdatePhase.IDLE, UpdatePhase.AVAILABLE, UpdatePhase.READY)) {
                TextButton(onClick = model::check) { Text("检查更新") }
            }
        }
    }
}
