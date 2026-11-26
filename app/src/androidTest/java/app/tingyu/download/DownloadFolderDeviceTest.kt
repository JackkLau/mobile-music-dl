package app.tingyu.download

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.MainActivity
import app.tingyu.MusicApp
import app.tingyu.data.bili.BilibiliRepository
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
        val folderName = "Weile-Test-" + UUID.randomUUID().toString().take(8)
        val marker = requireNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "folder-fixture.mp3")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$folderName/")
            }))
        val db = LibraryStore(app, folderName)
        val dir = File(app.cacheDir, folderName).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = dir
        }
        var tree: Uri? = null
        var saved: Uri? = null
        try {
            compose.onNodeWithText("下载").performClick()
            compose.onNodeWithText("选择文件夹").performClick()
            clickPicker { it.text?.toString() == folderName }
            clickPicker { it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString()?.lowercase() in setOf("use this folder", "使用此文件夹") }
            clickPicker { it.viewIdResourceName?.endsWith(":id/button1") == true ||
                it.text?.toString()?.lowercase() in setOf("allow", "允许") }
            compose.waitUntil(12_000) { folders.current.value.label.endsWith(folderName) }
            tree = Uri.parse(requireNotNull(folders.current.value.tree))
            assertEquals(folders.current.value, DownloadFolders(app).current.value)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })

            // Forty complete MPEG-1 Layer III frames, written through the real selected documents provider.
            val audio = ByteArray(417 * 40) { i -> when (i % 417) {
                0 -> 0xff.toByte(); 1 -> 0xfb.toByte(); 2 -> 0x90.toByte(); else -> 0
            } }
            val song = Track("netease-321", 321, "文件夹测试", durationMs = 1045, source = MusicSource.NETEASE)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                    .header("Content-Length", audio.size.toString()).body(audio.toResponseBody("audio/mpeg".toMediaType())).build()
            }.build()
            val coordinator = DownloadCoordinator(context, BilibiliRepository({ "" }), db, { track ->
                AudioStream("https://m701.music.126.net/fixture.mp3", mimeType = "audio/mpeg", expectedDurationMs = track.durationMs,
                    expectedBytes = audio.size.toLong(), source = track.source)
            }, client, {}, downloadFolder = { folders.current.value })
            runBlocking {
                withTimeout(30_000) {
                    coordinator.enqueue(listOf(song))
                    coordinator.jobs.first { it.any { task -> task.status == DownloadStatus.QUEUED } }
                    coordinator.runPending {}
                    val job = coordinator.jobs.value.single()
                    assertEquals(job.error, DownloadStatus.COMPLETED, job.status)
                    saved = Uri.parse(requireNotNull(job.uri))
                    assertTrue(DocumentsContract.getDocumentId(saved!!).startsWith("primary:Music/$folderName/"))
                    LocalMusicManager(app, db).scan(tree)
                    assertEquals(1, db.readLocal().count { it.uri == saved.toString() })
                }
            }
            compose.onNodeWithText("恢复默认").performClick()
            compose.waitUntil(5000) { folders.current.value.tree == null }
            assertEquals("Music/微乐/", DownloadFolders(app).current.value.label)
            assertArrayEquals(audio, resolver.openInputStream(saved!!)!!.use { it.readBytes() })
        } finally {
            saved?.let { DocumentsContract.deleteDocument(resolver, it) }
            resolver.delete(marker, null, null)
            if (tree != null) {
                runBlocking { folders.select(null) }
                // Only remove the uniquely named, test-created directory.
                DocumentsContract.deleteDocument(resolver, DownloadFolders.root(tree))
                if (resolver.persistedUriPermissions.any { it.uri == tree })
                    resolver.releasePersistableUriPermission(tree, DownloadFolders.READ_WRITE)
            }
            db.close()
            app.deleteDatabase(folderName)
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
                    if (match(node)) {
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                    }
                    for (child in 0 until node.childCount) node.getChild(child)?.let(nodes::add)
                }
                visible = nodes.mapNotNull { it.text?.toString() }.take(35)
            }
            SystemClock.sleep(180)
        }
        throw AssertionError("System picker control missing. Visible labels: $visible")
    }
}
