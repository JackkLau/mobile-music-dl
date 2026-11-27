package app.tingyu.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tingyu.BuildConfig
import app.tingyu.update.AppUpdateViewModel
import app.tingyu.update.UpdatePhase
import kotlinx.coroutines.launch

private const val APK_MIME = "application/vnd.android.package-archive"

@Composable
internal fun AppUpdateCard(model: AppUpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val release = state.release
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun install(digest: String) = scope.launch {
        model.installIntent(digest)?.let { intent ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                model.feedback("请在页面打开时再次点安装")
                return@launch
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                model.feedback("此设备没有可用的安装程序，可将安装包另存到文件")
            } catch (_: SecurityException) {
                model.feedback("系统限制了安装，请检查设备设置")
            }
        }
    }
    var requestedDigest by rememberSaveable { mutableStateOf<String?>(null) }
    var installPermission by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var exportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installPermission = context.packageManager.canRequestPackageInstalls()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME)) {
        exportUri = it
        if (it == null) requestedDigest = null
    }
    // Settings or the picker may recreate this activity. Consume the OS result only
    // once the private APK has been restored and still matches the user's selection.
    LaunchedEffect(state.phase, installPermission, exportUri) {
        val permission = installPermission
        val destination = exportUri
        if (state.phase != UpdatePhase.CHECKING && (permission != null || destination != null)) {
            val matches = state.phase == UpdatePhase.READY && release?.sha256 == requestedDigest
            installPermission = null
            exportUri = null
            requestedDigest = null
            when {
                !matches -> model.feedback("安装包已变更或缓存已清理，请重新下载")
                permission == false -> model.feedback("未获得安装权限，可重新授权或将安装包另存到文件")
                permission == true -> install(release!!.sha256)
                destination != null -> model.saveCopy(destination)
            }
        }
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
                UpdatePhase.VERIFYING -> "正在复核安装包签名和完整性…"
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { (state.bytes.toFloat() / release!!.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                }
                UpdatePhase.AVAILABLE -> {
                    FilledTonalButton(onClick = model::download) { Text("下载安装包 · ${formatBytes(release!!.size)}") }
                }
                UpdatePhase.READY -> {
                    if (state.canInstall) {
                        FilledTonalButton(onClick = {
                            if (context.packageManager.canRequestPackageInstalls()) install(release!!.sha256)
                            else try {
                                requestedDigest = release!!.sha256
                                settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                            } catch (_: ActivityNotFoundException) {
                                model.feedback("无法打开安装权限设置，可将安装包另存到文件")
                            } catch (_: SecurityException) {
                                model.feedback("设备限制了安装权限，可将安装包另存到文件")
                            }
                        }) { Text("安装 ${release!!.version}") }
                    }
                    OutlinedButton(onClick = {
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
