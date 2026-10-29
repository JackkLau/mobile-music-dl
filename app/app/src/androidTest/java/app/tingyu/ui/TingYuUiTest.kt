package app.tingyu.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tingyu.model.*
import app.tingyu.debug.AudioUaMode
import app.tingyu.debug.DebugSettings
import app.tingyu.data.bili.UserAgentProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI contract tests. Fixtures never enter the product and no network is used here. */
@RunWith(AndroidJUnit4::class)
class TingYuUiTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("BV1test000001", 101, "测试钢琴曲", "测试作者", durationMs = 180_000)

    @Test fun anonymousEntryDoesNotRequireLogin() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(), actions) }
        compose.onNodeWithText("匿名模式").assertExists()
        compose.onNodeWithText("解析链接").assertIsNotEnabled()
        compose.onNodeWithText("从一个链接开始").assertExists()
        assertEquals(0, actions.loginCount)
    }

    @Test fun nonemptyLinkCanBeClearedWithoutReadingClipboard() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(input = "https://www.bilibili.com/video/BV1test000001"), actions) }
        compose.onNodeWithContentDescription("清空链接").performClick()
        assertEquals("", actions.inputValue)
        assertEquals(0, actions.parseCount)
    }

    @Test fun selectedDownloadsRequireExplicitConfirmation() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(result = ParseResult("测试收藏夹", listOf(track))), actions) }
        compose.onNodeWithTag("parse_list").performScrollToNode(hasContentDescription("选择 测试钢琴曲"))
        compose.onNodeWithContentDescription("选择 测试钢琴曲").performClick()
        compose.onNodeWithTag("parse_list").performScrollToNode(hasText("下载所选 1 个条目"))
        compose.onNodeWithText("下载所选 1 个条目").performClick()
        assertTrue(actions.downloaded.isEmpty())
        compose.onNodeWithText("开始下载").performClick()
        assertEquals(listOf(track), actions.downloaded)
    }

    @Test fun librarySearchClearRestoresLocalResults() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 1, library = listOf(LocalTrack(track, "content://test/101"))), actions) }
        compose.onNodeWithText("搜索标题或作者").performTextInput("不存在的曲目")
        compose.onNodeWithText("没有找到匹配的音频").assertExists()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("测试钢琴曲").assertExists()
    }

    @Test fun deletingLocalMusicWaitsForConfirmation() {
        val actions = RecordingActions()
        val local = LocalTrack(track, "content://test/101")
        compose.setContent { TingYuApp(AppUiState(tab = 1, library = listOf(local)), actions) }
        compose.onNodeWithTag("library_list").performScrollToNode(hasContentDescription("删除 测试钢琴曲"))
        compose.onNodeWithContentDescription("删除 测试钢琴曲").performClick()
        assertNull(actions.deleted)
        compose.onNodeWithText("保留").performClick()
        assertNull(actions.deleted)
        compose.onNodeWithContentDescription("删除 测试钢琴曲").performClick()
        compose.onNodeWithText("删除音频").performClick()
        assertEquals(local, actions.deleted)
    }

    @Test fun failedDownloadKeepsErrorAndOffersTargetedRetry() {
        val actions = RecordingActions()
        val failure = DownloadTask(track, status = DownloadStatus.FAILED, error = "网络连接已断开")
        compose.setContent { TingYuApp(AppUiState(tab = 2, downloads = listOf(failure)), actions) }
        compose.onNodeWithText("网络连接已断开").assertExists()
        compose.onNodeWithContentDescription("重试下载 测试钢琴曲").performClick()
        assertEquals(track.id, actions.retried)
    }

    @Test fun fullPlayerExposesPlaybackAndModeControls() {
        val actions = RecordingActions()
        val second = track.copy(cid = 102, title = "第二首测试曲目")
        compose.setContent {
            TingYuApp(AppUiState(playerVisible = true,
                playback = PlaybackState(track = track, queue = listOf(track, second), durationMs = track.durationMs)), actions)
        }
        compose.onNodeWithTag("player_list").performScrollToNode(hasContentDescription("下一首"))
        compose.onNodeWithContentDescription("下一首").performClick()
        compose.onNodeWithTag("player_list").performScrollToNode(hasContentDescription("切换播放模式，当前顺序播放"))
        compose.onNodeWithContentDescription("切换播放模式，当前顺序播放").performClick()
        assertEquals(1, actions.nextCount)
        assertEquals(1, actions.modeCount)
    }

    @Test fun bufferingCanBePausedFromMiniPlayer() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(playback = PlaybackState(track = track, queue = listOf(track), isLoading = true)), actions) }
        compose.onNodeWithContentDescription("暂停缓冲").assertIsEnabled().performClick()
        assertEquals(1, actions.toggleCount)
    }

    @Test fun sequentialLastTrackDisablesNext() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(playerVisible = true, playback = PlaybackState(track = track, queue = listOf(track))), actions) }
        compose.onNodeWithTag("player_list").performScrollToNode(hasContentDescription("下一首"))
        compose.onNodeWithContentDescription("下一首").assertIsNotEnabled()
    }

    @Test fun developerToolsAreHiddenUntilDebugModeEnabled() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true), actions) }
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
        compose.onNodeWithText("解析 UA").assertDoesNotExist()
        compose.onNodeWithTag("account_list").performScrollToNode(hasTestTag("app_version"))
        repeat(7) { compose.onNodeWithTag("app_version").performClick() }
        assertEquals(7, actions.versionTaps)
    }

    @Test fun enabledModeExposesOneDeveloperDestination() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true,
            debugSettings = DebugSettings(enabled = true)), actions) }
        compose.onNodeWithTag("account_list").performScrollToNode(hasText("开发者工具"))
        compose.onNodeWithText("开发者工具").performClick()
        assertTrue(actions.developerOpened)
        compose.onNodeWithText("解析 UA").assertDoesNotExist()
    }

    @Test fun probeNeedsAParsedTrackAndOffersNavigation() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true,
            debugSettings = DebugSettings(enabled = true), developerVisible = true), actions) }
        compose.onNodeWithTag("developer_list").performScrollToNode(hasText("开始同地址对照"))
        compose.onNodeWithText("开始同地址对照").assertIsNotEnabled()
        compose.onNodeWithText("去解析链接").assertExists()
    }

    @Test fun runningComparisonCanBeCancelled() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true,
            debugSettings = DebugSettings(enabled = true), developerVisible = true,
            result = ParseResult("测试", listOf(track)), probeRunning = true), actions) }
        compose.onNodeWithTag("developer_list").performScrollToNode(hasText("取消对照"))
        compose.onNodeWithText("取消对照").performClick()
        assertEquals(1, actions.probeCancellations)
    }

    @Test fun releaseStateCannotDisplayDeveloperScreen() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = false,
            debugSettings = DebugSettings(enabled = true), developerVisible = true), actions) }
        compose.onNodeWithTag("developer_list").assertDoesNotExist()
        compose.onNodeWithTag("app_version").assertDoesNotExist()
        compose.onNodeWithText("开发者工具").assertDoesNotExist()
    }

    private class RecordingActions : AppActions {
        var inputValue: String? = null
        var parseCount = 0
        var loginCount = 0
        var nextCount = 0
        var modeCount = 0
        var toggleCount = 0
        var downloaded = emptyList<Track>()
        var deleted: LocalTrack? = null
        var retried: String? = null
        var versionTaps = 0
        var developerOpened = false
        var probeCancellations = 0
        override fun input(value: String) { inputValue = value }
        override fun parse() { parseCount++ }
        override fun loadMore() {}
        override fun tab(index: Int) {}
        override fun play(track: Track, queue: List<Track>) {}
        override fun download(tracks: List<Track>) { downloaded = tracks }
        override fun togglePlayback() { toggleCount++ }
        override fun seek(positionMs: Long) {}
        override fun next() { nextCount++ }
        override fun previous() {}
        override fun cycleMode() { modeCount++ }
        override fun showPlayer(show: Boolean) {}
        override fun showQueue(show: Boolean) {}
        override fun cancelDownload(id: String) {}
        override fun retryDownload(id: String) { retried = id }
        override fun login() { loginCount++ }
        override fun logout() {}
        override fun sessionMode(mode: SessionMode) {}
        override fun dismissMessage() {}
        override fun deleteLocal(track: LocalTrack) { deleted = track }
        override fun versionTap() { versionTaps++ }
        override fun showDeveloperTools(show: Boolean) { developerOpened = show }
        override fun disableDebugMode() {}
        override fun parseUa(value: UserAgentProfile) {}
        override fun audioUa(value: AudioUaMode) {}
        override fun debugLogging(enabled: Boolean) {}
        override fun selectProbeTrack(id: String) {}
        override fun startUaComparison() {}
        override fun cancelUaComparison() { probeCancellations++ }
    }
}
