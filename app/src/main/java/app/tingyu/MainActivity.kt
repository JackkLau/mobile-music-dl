package app.tingyu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import app.tingyu.model.AppActions
import app.tingyu.model.Track
import app.tingyu.ui.TingYuApp
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.continueDownloadAfterPermission()
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Even a denied request can still discover this installation's own downloads.
        model.scanLocalMusic()
    }
    private val localFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val uris = buildList {
                    data.data?.let(::add)
                    data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
                }.distinct()
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.importLocalFiles(uris, flags)
            }
        }
    }
    private val localDeleteLaunchers = mutableMapOf<String, ActivityResultLauncher<IntentSenderRequest>>()

    private fun deleteLauncher(operationId: String): ActivityResultLauncher<IntentSenderRequest> =
        localDeleteLaunchers.getOrPut(operationId) {
            // The no-LifecycleOwner overload can register while STARTED. Each result
            // retains its original operation ID rather than using the latest delete target.
            activityResultRegistry.register("local-delete-$operationId", ActivityResultContracts.StartIntentSenderForResult()) { result ->
                model.completeDeleteConsent(operationId, result.resultCode == Activity.RESULT_OK)
                localDeleteLaunchers.remove(operationId)?.unregister()
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.pendingDeleteResultId?.let(::deleteLauncher)
        if (savedInstanceState == null) acceptIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.deleteConsent.collect { request ->
                    if (request != null) {
                        model.consumeDeleteConsent(request.operationId)
                        try { deleteLauncher(request.operationId).launch(IntentSenderRequest.Builder(request.request).build()) }
                        catch (_: Exception) { model.completeDeleteConsent(request.operationId, false) }
                    }
                }
            }
        }
        val actions = object : AppActions by model {
            override fun scanLocalMusic() {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) model.scanLocalMusic()
                else audioPermission.launch(permission)
            }
            override fun selectLocalFiles() {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try { localFiles.launch(picker) }
                catch (_: Exception) { model.localPickerUnavailable() }
            }
            override fun download(tracks: List<Track>) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("asked_notification", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notification", true).apply()
                    model.deferDownloadForPermission(tracks)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else model.download(tracks)
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            TingYuApp(state, actions)
        }
    }
    override fun onDestroy() {
        localDeleteLaunchers.values.toList().forEach { it.unregister() }
        localDeleteLaunchers.clear()
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::receiveShare)
    }
}
