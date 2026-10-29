package app.tingyu.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import app.tingyu.lyrics.LyricsDocument
import app.tingyu.model.AppActions
import app.tingyu.model.AppUiState
import app.tingyu.model.PlaybackState
import app.tingyu.model.Track
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for a scrollable song/lyric area with a separate, fixed transport area. */
@RunWith(AndroidJUnit4::class)
class PlayerBottomControlsTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("BV1test000001", 101, "合成歌曲", "测试歌手", durationMs = 180_000)
    private val second = track.copy(bvid = "BV1test000002", cid = 102, title = "下一首合成歌曲")

    @Test fun longSongDetailsScrollWithoutMovingOrDisablingTransport() {
        val actions = RecordingActions()
        val longTitle = track.copy(title = "这是一段用于检验小屏滚动的完整歌曲标题".repeat(12))
        compose.setContent { PlayerDialog(state(longTitle), actions) }
        val original = controlsBounds()
        compose.onNodeWithTag("player_list").performScrollToNode(hasContentDescription("下载音频"))
        compose.onNodeWithContentDescription("下载音频").assertIsDisplayed()
        assertSameBounds(original)
        compose.onNodeWithTag("player_list").performScrollToIndex(1)
        assertSameBounds(original)
        control("下一首").assertIsDisplayed().performClick()
        control("上一首").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player_toggle").assertIsDisplayed().performClick()
        control("切换播放模式，当前顺序播放").performClick()
        control("打开播放队列").performClick()
        assertEquals(1, actions.next)
        assertEquals(1, actions.previous)
        assertEquals(1, actions.toggles)
        assertEquals(1, actions.modes)
        assertEquals(1, actions.queues)
    }

    @Test fun scrollingLyricsKeepsTransportAndSeekingAvailable() {
        val actions = RecordingActions()
        val lyrics = (0..160).joinToString("\n") { index ->
            "[%02d:%02d]合成歌词第 %d 行".format(index / 60, index % 60, index)
        }
        compose.setContent { PlayerDialog(state().copy(lyrics = LyricsDocument(syncedText = lyrics, source = "本地导入")), actions) }
        val original = controlsBounds()
        compose.onNodeWithTag("lyrics_tab").performClick()
        // A real drag pauses following in both full and compact layouts, without depending on toolbar placement.
        compose.onNodeWithTag("lyrics_lines").performTouchInput { swipeUp() }
        compose.onNodeWithTag("lyrics_lines").performScrollToIndex(60)
        compose.onNodeWithText("合成歌词第 60 行").assertIsDisplayed()
        assertSameBounds(original)
        control("后退 10 秒").performClick()
        assertEquals(20_000L, actions.seeks.last())
        control("前进 10 秒").performClick()
        assertEquals(40_000L, actions.seeks.last())
        control("播放进度").performSemanticsAction(SemanticsActions.SetProgress) { it(90_000f) }
        assertEquals(90_000L, actions.seeks.last())
        compose.onNodeWithTag("player_toggle").performClick()
        assertEquals(1, actions.toggles)
        assertSameBounds(original)
    }

    @Test fun longPlainLyricsAndFailureMessagesCannotPushControlsAway() {
        val actions = RecordingActions()
        val current = mutableStateOf(state().copy(lyrics = LyricsDocument(
            plainText = (0..150).joinToString("\n") { "无时间标记的合成歌词第 $it 行" }, source = "本地导入")))
        compose.setContent { PlayerDialog(current.value, actions) }
        compose.onNodeWithTag("lyrics_tab").performClick()
        val original = controlsBounds()
        compose.onNodeWithTag("plain_lyrics").performScrollToIndex(80)
        compose.onNodeWithText("无时间标记的合成歌词第 80 行").assertIsDisplayed()
        assertSameBounds(original)
        compose.runOnIdle {
            current.value = current.value.copy(
                lyricsError = "歌词服务暂时不可用，请稍后重试。".repeat(12),
                playback = current.value.playback.copy(error = "测试网络失败"))
        }
        assertSameBounds(original)
        control("下一首").performClick()
        assertEquals(1, actions.next)
        compose.onNodeWithText("播放中断 · 重新播放").performClick()
        assertEquals(1, actions.retries)
    }

    @Test fun bufferingKeepsPauseReachableWhileProgressIsDisabled() {
        val actions = RecordingActions()
        val current = mutableStateOf(state())
        compose.setContent { PlayerDialog(current.value, actions) }
        val original = controlsBounds()
        compose.runOnIdle { current.value = current.value.copy(playback = current.value.playback.copy(isLoading = true)) }
        assertSameBounds(original)
        control("暂停缓冲").assertIsDisplayed().performClick()
        control("播放进度").assertIsNotEnabled()
        assertEquals(1, actions.toggles)
    }

    @Test fun lyricsKeepAReadableViewportAndOptionsReachableOnShortScreens() {
        val actions = RecordingActions()
        val lyrics = (0..160).joinToString("\n") { index ->
            "[%02d:%02d]短屏合成歌词第 %d 行".format(index / 60, index % 60, index)
        }
        compose.setContent { PlayerDialog(state().copy(lyrics = LyricsDocument(syncedText = lyrics, source = "本地导入")), actions) }
        compose.onNodeWithTag("lyrics_tab").performClick()
        val original = controlsBounds()
        val body = compose.onNodeWithTag("lyrics_lines").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val minimumHeight = with(compose.density) { 48.dp.toPx() }
        assertTrue("At least one full lyric touch target must fit above the fixed controls: ${body.height}px", body.height >= minimumHeight)
        if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(200)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val directory = File(instrumentation.targetContext.cacheDir, "song-ui").apply { mkdirs() }
                File(directory, "lyrics-viewport.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        compose.onNodeWithContentDescription("歌词选项").assertIsDisplayed().performClick()
        compose.onNodeWithText("歌词选项").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed().performClick()
        assertSameBounds(original)
        compose.onNodeWithTag("player_toggle").performClick()
        assertEquals(1, actions.toggles)
    }

    private fun state(song: Track = track) = AppUiState(playerVisible = true,
        playback = PlaybackState(track = song, queue = listOf(song, second), durationMs = 180_000, positionMs = 30_000))

    private fun control(label: String) = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("player_controls")))

    private fun controlsBounds(): Rect {
        compose.onNodeWithTag("player_controls").assertIsDisplayed()
        compose.onNodeWithTag("player_controls").assert(!hasAnyAncestor(hasScrollAction()))
        control("播放进度").assertIsDisplayed()
        control("下一首").assertIsDisplayed()
        return compose.onNodeWithTag("player_controls").fetchSemanticsNode().boundsInRoot.also {
            assertTrue("Transport must have usable bounds", it.width > 0 && it.height > 0)
        }
    }

    private fun assertSameBounds(before: Rect) {
        val after = controlsBounds()
        assertEquals(before.left, after.left, 1f)
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.right, after.right, 1f)
        assertEquals(before.bottom, after.bottom, 1f)
    }

    private class RecordingActions : AppActions by noOpActions() {
        var next = 0
        var previous = 0
        var toggles = 0
        var modes = 0
        var queues = 0
        var retries = 0
        val seeks = mutableListOf<Long>()
        override fun next() { next++ }
        override fun previous() { previous++ }
        override fun togglePlayback() { toggles++ }
        override fun cycleMode() { modes++ }
        override fun showQueue(show: Boolean) { if (show) queues++ }
        override fun seek(positionMs: Long) { seeks += positionMs }
        override fun play(track: Track, queue: List<Track>) { retries++ }
    }

    companion object {
        // Only the transport boundary is recorded; unrelated commands must never perform real work.
        private fun noOpActions(): AppActions = Proxy.newProxyInstance(AppActions::class.java.classLoader,
            arrayOf(AppActions::class.java)) { _, _, _ -> null } as AppActions
    }
}
