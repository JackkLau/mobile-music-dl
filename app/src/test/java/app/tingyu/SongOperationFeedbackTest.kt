package app.tingyu

import app.tingyu.model.*
import org.junit.Assert.*
import org.junit.Test

class SongOperationFeedbackTest {
    private val first = Track("BV1test000001", 1, "歌曲甲")
    private val second = first.copy(page = 2, title = "歌曲乙")

    @Test fun lateImportErrorDoesNotOverwriteAnotherSongsPanel() {
        val state = AppUiState(playback = PlaybackState(track = second), lyricsError = "乙的原有提示")
        val result = state.songFailure(first, "导入失败", lyrics = true)
        assertEquals("乙的原有提示", result.lyricsError)
        assertNull(result.musicError)
        assertEquals("歌曲甲：导入失败", result.message)
    }

    @Test fun fixedEditorReceivesItsOwnFailureEvenAfterPlaybackChanges() {
        val state = AppUiState(playback = PlaybackState(track = second), musicEditorVisible = true, musicEditorTrack = first)
        val result = state.songFailure(first, "保存失败")
        assertEquals("保存失败", result.musicError)
        assertNull(result.lyricsError)
        assertEquals(first, result.musicEditorTrack)
    }

    @Test fun currentLyricsErrorRemainsInlineAndRetryable() {
        val state = AppUiState(playback = PlaybackState(track = first))
        assertEquals("导入失败", state.songFailure(first, "导入失败", lyrics = true).lyricsError)
    }
}
