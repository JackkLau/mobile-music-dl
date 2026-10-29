package app.tingyu.ui

import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tingyu.model.*
import app.tingyu.debug.AudioUaMode
import app.tingyu.debug.DebugSettings
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.lyrics.LyricsDocument
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI contract tests. Fixtures never enter the product and no network is used here. */
@RunWith(AndroidJUnit4::class)
class TingYuUiTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("BV1test000001", 101, "测试钢琴曲", "测试作者", durationMs = 180_000)

    @Test fun lyricsLookupIsExplicitAndCanBeCancelled() {
        val actions = RecordingActions()
        val state = mutableStateOf(AppUiState(playerVisible = true, playback = PlaybackState(track = track)))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithTag("lyrics_tab").performClick()
        compose.onNodeWithText("还没有歌词").assertExists()
        assertEquals(0, actions.lyricsLookups)
        compose.onNodeWithText("联网查找").performClick()
        assertEquals(1, actions.lyricsLookups)
        compose.runOnIdle { state.value = state.value.copy(lyricsLoading = true) }
        compose.onNodeWithText("导入歌词").assertIsNotEnabled()
        compose.onNodeWithText("取消查找").performClick()
        assertEquals(1, actions.lyricsCancellations)
        compose.runOnIdle { state.value = state.value.copy(lyricsLoading = false, lyricsError = "没有匹配歌词，可导入文件") }
        compose.onNodeWithText("没有匹配歌词，可导入文件").assertExists()
        compose.onNodeWithText("导入歌词").performClick()
        assertEquals(track, actions.lyricsImport)
    }

    @Test fun syncedLyricsFollowPositionAndRemovalNeedsConfirmation() {
        val actions = RecordingActions()
        val state = mutableStateOf(AppUiState(playerVisible = true, playback = PlaybackState(track = track, positionMs = 1500),
            lyrics = LyricsDocument(syncedText = "[00:01]第一行合成歌词\n[00:03]第二行合成歌词", source = "本地导入")))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithTag("lyrics_tab").performClick()
        compose.onNodeWithText("第一行合成歌词").assertIsSelected()
        compose.runOnIdle { state.value = state.value.copy(playback = state.value.playback.copy(positionMs = 3500)) }
        compose.onNodeWithText("第二行合成歌词").assertIsSelected()
        compose.onNodeWithText("暂停跟随").performClick()
        compose.onNodeWithText("回到当前").performClick()
        captureScreenshot("lyrics")
        compose.onNodeWithContentDescription("移除当前歌词").performClick()
        assertEquals(0, actions.lyricsRemovals)
        compose.onNodeWithText("保留").performClick()
        compose.onNodeWithContentDescription("移除当前歌词").performClick()
        compose.onNodeWithText("移除歌词").performClick()
        assertEquals(1, actions.lyricsRemovals)
    }

    @Test fun plainLyricsAndInstrumentalHaveHonestStates() {
        val actions = RecordingActions()
        val state = mutableStateOf(AppUiState(playerVisible = true, playback = PlaybackState(track = track),
            lyrics = LyricsDocument(plainText = "没有时间标记的合成文字", source = "本地导入")))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithTag("lyrics_tab").performClick()
        compose.onNodeWithText("本地导入 · 纯文本歌词").assertExists()
        compose.onNodeWithText("没有时间标记的合成文字").assertExists()
        compose.runOnIdle { state.value = state.value.copy(lyrics = LyricsDocument(instrumental = true, source = "LRCLIB")) }
        compose.onNodeWithText("纯音乐，请欣赏").assertExists()
    }

    @Test fun songEditorPreservesSourceAndValidatesTitle() {
        val actions = RecordingActions()
        val song = track.copy(sourceTitle = "原始视频推广标题", sourceArtist = "视频上传者", metadataVersion = 1)
        compose.setContent { TingYuApp(AppUiState(playerVisible = true, musicEditorVisible = true,
            playback = PlaybackState(track = song)), actions) }
        compose.onNodeWithText("原视频：原始视频推广标题").assertExists()
        captureScreenshot("song-editor")
        compose.onNodeWithContentDescription("清空歌名").performClick()
        compose.onNodeWithText("保存信息").assertIsNotEnabled()
        compose.onNodeWithText("歌名").performTextInput("正确歌名")
        compose.onNodeWithText("歌手").performTextReplacement("正确歌手")
        compose.onNodeWithText("保存信息").performClick()
        assertEquals("正确歌名" to "正确歌手", actions.edited)
        compose.onNodeWithText("更换封面").performClick()
        assertEquals(song, actions.coverImport)
    }

    @Test fun songEditorKeepsDraftForOriginalSongWhenPlaybackChanges() {
        val actions = RecordingActions()
        val state = mutableStateOf(AppUiState(playerVisible = true, musicEditorVisible = true, musicEditorTrack = track,
            playback = PlaybackState(track = track)))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithText("歌名").performTextReplacement("尚未保存的新歌名")
        compose.runOnIdle { state.value = state.value.copy(playback = PlaybackState(track = track.copy(page = 2, title = "下一首歌曲"))) }
        compose.onNodeWithText("尚未保存的新歌名").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存信息").performClick()
        assertEquals(track, actions.editedTrack)
    }

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
        compose.onNodeWithText("搜索歌名或歌手").performTextInput("不存在的曲目")
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

    @Test fun localLibraryOffersScanAndFileImportWithoutLogin() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 1), actions) }
        compose.onNodeWithText("扫描已下载音乐").performClick()
        compose.onNodeWithText("导入本地音频").performClick()
        assertEquals(1, actions.scans)
        assertEquals(1, actions.imports)
        assertEquals(0, actions.loginCount)
    }

    @Test fun localDiscoveryProgressBlocksDuplicateActionsAndFailureKeepsFiles() {
        val actions = RecordingActions()
        val local = LocalTrack(track, "content://test/101")
        val state = mutableStateOf(AppUiState(tab = 1, library = listOf(local), libraryWorking = true))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithTag("library_progress").assertExists()
        compose.onNodeWithText("扫描已下载音乐").assertIsNotEnabled()
        compose.onNodeWithText("导入本地音频").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(libraryWorking = false, libraryError = "扫描未完成，已有记录已保留。") }
        compose.onNodeWithText("扫描未完成，已有记录已保留。").assertExists()
        compose.onNodeWithTag("library_list").performScrollToNode(hasText(track.title))
        compose.onNodeWithText(track.title).assertExists()
    }

    @Test fun localDeleteKeepsDialogUntilConfirmedResultAndAllowsRetry() {
        val actions = RecordingActions()
        val local = LocalTrack(track, "content://test/101")
        val state = mutableStateOf(AppUiState(tab = 1, library = listOf(local)))
        compose.setContent { TingYuApp(state.value, actions) }
        compose.onNodeWithTag("library_list").performScrollToNode(hasContentDescription("删除 ${track.title}"))
        compose.onNodeWithContentDescription("删除 ${track.title}").performClick()
        compose.onNodeWithText("删除音频").performClick()
        compose.runOnIdle { state.value = state.value.copy(deletingLocalId = track.id) }
        compose.onNodeWithText("删除音频").assertIsNotEnabled()
        compose.onNodeWithText("保留").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(deletingLocalId = null, localDeleteError = "文件未能删除，音乐记录已保留") }
        compose.onNodeWithText("文件未能删除，音乐记录已保留").assertExists()
        compose.onNodeWithText("删除音频").assertIsEnabled().performClick()
        assertEquals(local, actions.deleted)
        compose.runOnIdle { state.value = state.value.copy(library = emptyList(), localDeleteError = null) }
        compose.onNodeWithText("删除本地音频？").assertDoesNotExist()
    }

    @Test fun missingImportedTrackCannotBeDownloadedFromNetwork() {
        val actions = RecordingActions()
        val local = track.copy(localOnly = true)
        compose.setContent { TingYuApp(AppUiState(playerVisible = true,
            playback = PlaybackState(track = local, queue = listOf(local))), actions) }
        compose.onNodeWithTag("player_list").performScrollToNode(hasContentDescription("本地音频"))
        compose.onNodeWithContentDescription("本地音频").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下载音频").assertDoesNotExist()
        assertTrue(actions.downloaded.isEmpty())
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
        captureScreenshot("mobile-cover")
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

    @Test fun debugBuildProvidesDirectNetworkDebugEntry() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true), actions) }
        compose.onNodeWithTag("account_list").performScrollToNode(hasText("网络调试"))
        compose.onNodeWithText("网络调试").performClick()
        assertTrue(actions.developerOpened)
    }

    @Test fun probeNeedsAParsedTrackAndOffersNavigation() {
        val actions = RecordingActions()
        compose.setContent { TingYuApp(AppUiState(tab = 3, debugAvailable = true,
            debugSettings = DebugSettings(enabled = true), developerVisible = true), actions) }
        compose.onNodeWithTag("developer_list").performScrollToNode(hasText("开始同地址 UA 对照"))
        compose.onNodeWithText("开始同地址 UA 对照").assertIsNotEnabled()
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
        compose.onNodeWithText("网络调试").assertDoesNotExist()
    }

    private fun captureScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200) // Let the emulator present the already-idle Compose frame.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.cacheDir, "song-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
        var scans = 0
        var imports = 0
        var lyricsLookups = 0
        var lyricsCancellations = 0
        var lyricsRemovals = 0
        var lyricsImport: Track? = null
        var coverImport: Track? = null
        var edited: Pair<String, String>? = null
        var editedTrack: Track? = null
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
        override fun showMusicEditor(show: Boolean) {}
        override fun saveMusicInfo(track: Track, title: String, artist: String) { edited = title to artist; editedTrack = track }
        override fun findLyrics() { lyricsLookups++ }
        override fun cancelLyrics() { lyricsCancellations++ }
        override fun importLyrics(track: Track) { lyricsImport = track }
        override fun clearLyrics() { lyricsRemovals++ }
        override fun selectSongCover(track: Track) { coverImport = track }
        override fun resetSongCover(track: Track) {}
        override fun cancelDownload(id: String) {}
        override fun retryDownload(id: String) { retried = id }
        override fun login() { loginCount++ }
        override fun logout() {}
        override fun sessionMode(mode: SessionMode) {}
        override fun dismissMessage() {}
        override fun deleteLocal(track: LocalTrack) { deleted = track }
        override fun scanLocalMusic() { scans++ }
        override fun selectLocalFiles() { imports++ }
        override fun dismissLocalDeleteError() {}
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
