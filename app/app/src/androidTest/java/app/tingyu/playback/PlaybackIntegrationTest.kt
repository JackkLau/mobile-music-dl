package app.tingyu.playback

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.MainActivity
import app.tingyu.MusicApp
import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Room -> TrackMedia -> TrackDataSource -> ExoPlayer path.
 * All audio is generated locally. Run on a clean test installation: an existing
 * playback snapshot makes this test skip rather than replace a user's queue.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun localAudioPlaysSeeksAndChangesQueueItem() {
        val app = instrumentation.targetContext.applicationContext as MusicApp
        val library = app.container.library
        assumeTrue("Use a clean test installation; preserve existing playback state", runBlocking { library.readPlayer() == null })
        assumeTrue("Do not interrupt an existing playback service", !serviceRunning(app))

        val directory = File(app.cacheDir, "playback-test-${UUID.randomUUID()}")
        val bvid = "BV" + UUID.randomUUID().toString().replace("-", "").take(10)
        val tracks = listOf(
            Track(bvid, 1, "Local PCM fixture A", durationMs = 12_000, page = 1),
            Track(bvid, 2, "Local PCM fixture B", durationMs = 12_000, page = 2),
        )
        // Do not overwrite even a coincidentally matching library entry.
        assumeTrue("Fixture IDs must be unused", runBlocking { tracks.all { library.saved(it.id) == null } })
        var scenario: ActivityScenario<MainActivity>? = null
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var failure: Throwable? = null
        try {
            assertTrue(directory.mkdir())
            tracks.forEachIndexed { index, track ->
                val file = File(directory, "tone-$index.wav")
                writePcmWav(file, 220.0 * (index + 1))
                runBlocking { library.saveLocal(LocalTrack(track, Uri.fromFile(file).toString())) }
            }
            // A visible Activity makes starting media playback legal on recent Android versions.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val connection = onMain {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            }
            future = connection
            val player = connection.get(15, TimeUnit.SECONDS)
            controller = player
            onMain {
                player.volume = 0f // Exercise decoding and the playback clock without audible test tones.
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
                player.setMediaItems(tracks.map(TrackMedia::item), 0, 0)
                player.prepare()
            }
            awaitPlayer(player, "first WAV prepared") {
                it.playbackState == Player.STATE_READY && it.currentMediaItem?.mediaId == tracks[0].id
            }
            onMain {
                assertEquals(2, player.mediaItemCount)
                assertEquals(tracks[0], TrackMedia.track(player.currentMediaItem))
                assertTrue("Duration must come from the decoded WAV", player.duration in 11_900L..12_100L)
                player.play()
            }
            awaitPlayer(player, "real playback clock advances") { it.isPlaying && it.currentPosition >= 300 }
            onMain { player.pause() }
            awaitPlayer(player, "playback pauses") { !it.isPlaying && !it.playWhenReady }

            onMain { player.seekTo(5_000); player.play() }
            awaitPlayer(player, "seeked audio resumes beyond five seconds") {
                it.isPlaying && it.currentPosition in 5_250L..10_000L
            }
            onMain { player.seekToNextMediaItem() }
            awaitPlayer(player, "second WAV actually plays") {
                it.currentMediaItem?.mediaId == tracks[1].id && it.isPlaying && it.currentPosition in 250L..5_000L
            }
            onMain {
                assertEquals(1, player.currentMediaItemIndex)
                assertEquals(tracks[1], TrackMedia.track(player.currentMediaItem))
                assertTrue(player.hasPreviousMediaItem())
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // All cleanup is scoped to this test's queue, randomly named rows and private directory.
            val cleanupErrors = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanupErrors += error } }
            clean { onMain { controller?.let { it.pause(); it.stop(); it.clearMediaItems() } } }
            clean { onMain { future?.let(MediaController::releaseFuture) } }
            clean { scenario?.close() }
            clean {
                app.stopService(Intent(app, PlaybackService::class.java))
                awaitCondition("playback service stops before deleting fixture files") { !serviceRunning(app) }
                instrumentation.waitForIdleSync()
                runBlocking {
                    library.readPlayer()?.let { snapshot ->
                        val ids = tracks.map { it.id }.toSet()
                        check(library.decodeQueue(snapshot).all { it.id in ids }) { "Refusing to remove another queue" }
                        library.clearPlayer()
                    }
                }
            }
            tracks.forEach { track -> clean { runBlocking { library.removeLocal(track.id) } } }
            clean { assertTrue("Remove only this test's private audio directory", !directory.exists() || directory.deleteRecursively()) }
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = AssertionError("Playback fixture cleanup failed")
                cleanupErrors.forEach(cleanupFailure::addSuppressed)
                failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun awaitPlayer(player: MediaController, description: String, condition: (MediaController) -> Boolean) {
        awaitCondition(description) {
            onMain {
                check(player.isConnected) { "Controller disconnected while waiting for $description" }
                player.playerError?.let { throw AssertionError("Playback failed while waiting for $description", it) }
                condition(player)
            }
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    @Suppress("DEPRECATION") // On API 26+, this still reports the calling application's own services.
    private fun serviceRunning(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == PlaybackService::class.java.name }

    private fun writePcmWav(file: File, frequencyHz: Double) {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 12
        val dataSize = sampleCount * 2
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bytes.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        bytes.putShort(2).putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        repeat(sampleCount) { sample ->
            bytes.putShort((sin(2.0 * Math.PI * frequencyHz * sample / sampleRate) * 2_000).toInt().toShort())
        }
        file.writeBytes(bytes.array())
    }
}
