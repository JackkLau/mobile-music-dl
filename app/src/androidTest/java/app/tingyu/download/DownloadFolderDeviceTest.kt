package app.tingyu.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.MainActivity
import app.tingyu.MusicApp
import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.bili.NetworkProfile
import app.tingyu.data.local.LibraryStore
import app.tingyu.data.local.LocalMusicManager
import app.tingyu.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@UnstableApi
class DownloadFolderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pickerGrantsSurviveRestartAndDownloadedAudioSurvivesReset() {
        val app = compose.activity.application as MusicApp
        val folders = app.container.downloadFolders
        val resolver = app.contentResolver
        val fixture = "Weile-Test-" + UUID.randomUUID().toString().take(8)
        val db = LibraryStore(app, fixture)
        val dir = File(app.cacheDir, fixture).apply { mkdirs() }
        val previous = folders.current.value
        val previousGrants = resolver.persistedUriPermissions.map { it.uri }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = dir
        }
        var tree: Uri? = null
        var saved: Uri? = null
        try {
            runBlocking { folders.select(null) }
            compose.onNodeWithText("下载").performClick()
            compose.onNodeWithText("选择文件夹").performClick()
            // Select Music itself, distinct from the default MediaStore Music/微乐/ subdirectory.
            clickPicker { it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString()?.lowercase() in setOf("use this folder", "使用此文件夹") }
            clickPicker { it.viewIdResourceName?.endsWith(":id/button1") == true ||
                it.text?.toString()?.lowercase() in setOf("allow", "允许") }
            compose.waitUntil(12_000) { folders.current.value.tree != null }
            tree = Uri.parse(requireNotNull(folders.current.value.tree))
            assertEquals("primary:Music", DocumentsContract.getTreeDocumentId(tree))
            assertEquals("Music", folders.current.value.label)
            assertEquals(folders.current.value, DownloadFolders(app).current.value)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })

            // Forty complete MPEG-1 Layer III frames, written through the real selected documents provider.
            val audio = ByteArray(417 * 40) { i -> when (i % 417) {
                0 -> 0xff.toByte(); 1 -> 0xfb.toByte(); 2 -> 0x90.toByte(); else -> 0
            } }
            val song = Track("netease-321", 321, fixture, durationMs = 1045, source = MusicSource.NETEASE)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                    .header("Content-Length", audio.size.toString()).body(audio.toResponseBody("audio/mpeg".toMediaType())).build()
            }.build()
            val trace = mutableListOf<String>()
            val bili = BilibiliRepository({ "" }, { NetworkProfile(loggingEnabled = true) }, { trace.add(it) })
            val coordinator = DownloadCoordinator(context, bili, db, { track ->
                AudioStream("https://m701.music.126.net/fixture.mp3", mimeType = "audio/mpeg", expectedDurationMs = track.durationMs,
                    expectedBytes = audio.size.toLong(), source = track.source)
            }, client, {}, downloadFolder = { folders.current.value })
            runBlocking {
                withTimeout(30_000) {
                    suspend fun downloadSong(step: String): Uri {
                        trace.clear()
                        coordinator.enqueue(listOf(song))
                        coordinator.jobs.first { it.any { task -> task.status == DownloadStatus.QUEUED } }
                        coordinator.runPending {}
                        val job = coordinator.jobs.value.single()
                        assertEquals("$step: ${job.error}, trace=$trace", DownloadStatus.COMPLETED, job.status)
                        return Uri.parse(requireNotNull(job.uri))
                    }
                    saved = downloadSong("First SAF download")
                    assertEquals("primary:Music", DocumentsContract.getDocumentId(saved!!).substringBeforeLast('/'))
                    LocalMusicManager(app, db).scan(tree)
                    assertEquals(1, db.readLocal().count { it.uri == saved.toString() })
                    assertTrue(DocumentsContract.deleteDocument(resolver, saved!!))
                    assertTrue("Parent must confirm removal", DownloadFolders.confirmsFileRemoved(resolver, saved!!))
                    saved = null
                    saved = downloadSong("Download after external deletion")
                    assertEquals(saved.toString(), db.saved(song.id)?.uri)
                }
            }
            compose.onNodeWithText("恢复默认").performClick()
            compose.waitUntil(5000) { folders.current.value.tree == null }
            assertEquals("Music/微乐/", DownloadFolders(app).current.value.label)
            assertArrayEquals(audio, resolver.openInputStream(saved!!)!!.use { it.readBytes() })
        } finally {
            saved?.let { DocumentsContract.deleteDocument(resolver, it) }
            runBlocking { folders.select(previous.tree?.let(Uri::parse), DownloadFolders.READ_WRITE) }
            if (tree != null && tree !in previousGrants && resolver.persistedUriPermissions.any { it.uri == tree })
                resolver.releasePersistableUriPermission(tree, DownloadFolders.READ_WRITE)
            db.close()
            app.deleteDatabase(fixture)
            dir.deleteRecursively()
        }
    }

    private fun clickPicker(match: (AccessibilityNodeInfo) -> Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var visible = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString()?.contains("documentsui") == true) {
                val nodes = mutableListOf(root)
                var index = 0
                while (index < nodes.size) {
                    val node = nodes[index++]
                    for (child in 0 until node.childCount) node.getChild(child)?.let(nodes::add)
                }
                visible = nodes.mapNotNull { it.text?.toString() }
                val target = nodes.firstOrNull { it.isEnabled && it.isClickable && match(it) }
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(180)
        }
        throw AssertionError("System picker control missing. Visible labels: $visible")
    }
}
