package app.tingyu.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tingyu.data.music.MusicLoginRequired
import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.local.LibraryStore
import app.tingyu.model.*
import java.io.File
import java.io.IOException
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicDownloadIntegrationTest {
    @Test fun failedTaskCanRetrySaveRealMp3AndRemoveOnlyItsHistory() = runBlocking {
        withTimeout(30_000) {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val name = "test-music-" + UUID.randomUUID()
            val dir = File(app.cacheDir, name).apply { mkdirs() }
            val context = object : ContextWrapper(app) { override fun getFilesDir() = dir; override fun getApplicationContext(): Context = this }
            val db = LibraryStore(app,name)
            val song = Track("netease-123", 123,"测试音乐", durationMs = 1045,
                sourceUrl = "https://music.163.com/song?id=123", source = MusicSource.NETEASE, metadataVersion = 1)
            // Forty complete silent MPEG-1 layer III frames at 44.1 kHz / 128 kbps.
            val audio = ByteArray(417 * 40) { idx ->
                when (idx % 417) { 0 -> 0xff.toByte(); 1 -> 0xfb.toByte(); 2 -> 0x90.toByte(); else -> 0 }
            }
            var available = false
            var reparses = 0
            var uri: Uri? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertNull(chain.request().header("Cookie"))
                assertEquals("bytes=0-",chain.request().header("Range"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                    .header("Content-Length", audio.size.toString()).body(audio.toResponseBody("audio/mpeg".toMediaType())).build()
            }.build()
            val coordinator = DownloadCoordinator(context,BilibiliRepository({ "" }),db,{ track ->
                if (!available) throw MusicLoginRequired(track.source, "业务码 301")
                AudioStream("https://m701.music.126.net/fixture.mp3", mimeType = "audio/mpeg",
                    expectedDurationMs = track.durationMs, expectedBytes = audio.size.toLong(), source = track.source)
            },client,{}, refreshMusic = { reparses++; it })
            try {
                coordinator.enqueue(listOf(song))
                coordinator.jobs.first { it.any { job -> job.status == DownloadStatus.QUEUED } }
                coordinator.runPending {}
                assertEquals(DownloadStatus.FAILED,coordinator.jobs.value.single().status)
                val challenge = coordinator.jobs.value.single().loginRequestId!!
                coordinator.finishMusicLogin("obsolete", true)
                assertEquals(challenge, coordinator.jobs.value.single().loginRequestId)
                coordinator.finishMusicLogin(challenge, true)
                coordinator.runPending {}
                assertEquals(1, reparses)
                assertEquals(DownloadStatus.FAILED, coordinator.jobs.value.single().status)
                assertNull(coordinator.jobs.value.single().loginRequestId) // Rejection after login stays actionable without looping.
                coordinator.retry(song.id)
                coordinator.runPending {}
                val second = coordinator.jobs.value.single().loginRequestId!!
                coordinator.finishMusicLogin(second, false)
                assertNull(coordinator.jobs.value.single().loginRequestId)
                coordinator.retry(song.id)
                coordinator.runPending {}
                available = true
                val fresh = coordinator.jobs.value.single().loginRequestId!!
                coordinator.finishMusicLogin(fresh, true)
                coordinator.finishMusicLogin(fresh, true)
                coordinator.runPending {}
                val task = coordinator.jobs.value.single()
                assertEquals(task.error, DownloadStatus.COMPLETED,task.status)
                assertEquals(2, reparses)
                uri = Uri.parse(task.uri!!)
                assertEquals("audio/mpeg",app.contentResolver.getType(uri))
                val saved = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertArrayEquals(audio,saved)
                coordinator.remove(song.id)
                assertTrue(db.readDownloads().isEmpty())
                assertEquals(uri.toString(),db.saved(song.id)?.uri)
                assertTrue(app.contentResolver.openInputStream(uri)!!.use { it.read() >= 0 })
            } finally {
                uri?.let { app.contentResolver.delete(it,null,null) }
                db.close()
                app.deleteDatabase(name)
                dir.deleteRecursively()
            }
        }
        Unit
    }

    @Test fun restoredStoppedTasksCanBeRemovedAndNeverResurrectedByRetry() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-stopped-" + UUID.randomUUID()
        val dir = File(app.cacheDir,name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = dir; override fun getApplicationContext(): Context = this }
        val db = LibraryStore(app,name)
        try {
            val stopped = listOf(DownloadStatus.FAILED,DownloadStatus.CANCELLED,DownloadStatus.DOWNLOADING)
            val tracks = stopped.mapIndexed { i,status ->
                val song = Track("BV123456789$i",i.toLong(),"旧任务")
                db.saveDownload(DownloadTask(song,status))
                song
            }
            val coordinator = DownloadCoordinator(context,BilibiliRepository({ "" }),db,{ error("Unexpected network request") }, launchService = {})
            withTimeout(10_000) { coordinator.jobs.first { it.size == 3 } }
            assertEquals(DownloadStatus.INTERRUPTED,coordinator.jobs.value.single { it.track.id == tracks.last().id }.status)
            for (song in tracks) {
                coordinator.remove(song.id)
                coordinator.retry(song.id)
            }
            assertTrue(db.readDownloads().isEmpty())
            assertTrue(coordinator.jobs.value.isEmpty())
        } finally {
            db.close()
            app.deleteDatabase(name)
            dir.deleteRecursively()
        }
    }
}
