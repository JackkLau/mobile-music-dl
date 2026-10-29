package app.tingyu

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.tingyu.model.AppActions
import app.tingyu.model.Track
import app.tingyu.ui.TingYuApp

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.continueDownloadAfterPermission()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptIntent(intent)
        val actions = object : AppActions by model {
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
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::receiveShare)
    }
}
