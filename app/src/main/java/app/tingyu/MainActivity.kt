package app.tingyu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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
import app.tingyu.auth.MusicAccountActivity
import app.tingyu.model.MusicLoginChallenge
import app.tingyu.model.MusicLoginMethod
import app.tingyu.model.AppActions
import app.tingyu.model.MusicSource
import app.tingyu.data.music.MusicShareLink
import app.tingyu.download.DownloadFolders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.tingyu.model.Track
import app.tingyu.sharing.musicShareIntent
import app.tingyu.ui.TingYuApp
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.continueDownloadAfterPermission()
    }
    private var launchedMusicLogin: String? = null
    private val musicAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = launchedMusicLogin
        launchedMusicLogin = null
        if (expected != null) model.finishMusicLogin(expected,
            result.resultCode == Activity.RESULT_OK && result.data?.getStringExtra("challenge") == expected)
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Even a denied request can still discover this installation's own downloads.
        model.scanLocalMusic()
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            data.data?.let { tree -> model.setDownloadFolder(tree, data.flags) }
        }
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
    private val lyricsFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> model.finishSongFile("lyrics", uri) }
    private val coverFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> model.finishSongFile("cover", uri) }

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
        launchedMusicLogin = savedInstanceState?.getString("music_login_activity")
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
            override fun startMusicLogin(challenge: MusicLoginChallenge, method: MusicLoginMethod) {
                if (launchedMusicLogin != null || model.state.value.musicLogin?.id != challenge.id) return
                launchedMusicLogin = challenge.id
                try {
                    musicAccount.launch(Intent(this@MainActivity, MusicAccountActivity::class.java)
                        .putExtra("source", challenge.source.name).putExtra("method", method.name).putExtra("challenge", challenge.id))
                } catch (_: android.content.ActivityNotFoundException) {
                    launchedMusicLogin = null
                    model.dismissMusicLogin(challenge.id)
                }
            }
            override fun importLyrics(track: Track) {
                if (!model.beginSongFile("lyrics", track)) return
                try { lyricsFile.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }
                catch (_: Exception) { model.finishSongFile("lyrics", null); model.localPickerUnavailable() }
            }
            override fun selectSongCover(track: Track) {
                if (!model.beginSongFile("cover", track)) return
                try { coverFile.launch(arrayOf("image/*")) }
                catch (_: Exception) { model.finishSongFile("cover", null); model.localPickerUnavailable() }
            }
            override fun scanLocalMusic() {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) model.scanLocalMusic()
                else audioPermission.launch(permission)
            }
            override fun selectDownloadFolder() {
                val initial = model.state.value.downloadFolder.tree?.let { DownloadFolders.root(Uri.parse(it)) }
                    ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
                try { folderPicker.launch(intent) } catch (_: android.content.ActivityNotFoundException) { model.folderPickerUnavailable() }
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
            override fun shareMusic(track: Track) {
                val local = model.state.value.library.find { it.track.id == track.id }
                lifecycleScope.launch {
                    val message = try {
                        val chooser = musicShareIntent(this@MainActivity, track, local)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startActivity(chooser)
                        null
                    } catch (e: java.io.IOException) {
                        e.localizedMessage ?: "无法分享这首歌"
                    } catch (_: android.content.ActivityNotFoundException) {
                        "未找到可以接收分享的应用"
                    } catch (_: SecurityException) {
                        "无法读取音频，请重新导入后分享"
                    }
                    if (message != null) android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun openMusicPage(track: Track) {
                val url = track.sourceUrl.toHttpUrlOrNull() ?: return
                val song = runCatching { MusicShareLink.direct(url, track.source) }.getOrNull() ?: return
                val official = if (song.source == MusicSource.NETEASE)
                    "https://music.163.com/song?id=${song.id}" else "https://y.qq.com/n/ryqq/songDetail/${song.id}"
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(official))) }
                catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(this@MainActivity, "没有可打开歌曲页的应用", android.widget.Toast.LENGTH_SHORT).show() }
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("music_login_activity", launchedMusicLogin)
        super.onSaveInstanceState(outState)
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
