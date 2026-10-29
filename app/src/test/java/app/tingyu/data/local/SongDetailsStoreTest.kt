package app.tingyu.data.local

import app.tingyu.lyrics.LyricsDocument
import app.tingyu.model.MusicMetadata
import app.tingyu.model.Track
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongDetailsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = Track("BV1test000001", 42, "测试歌手《测试歌曲》【4K】", "视频上传者", "https://example.com/video.jpg", durationMs = 180000)

    @Test fun editsLyricsAndArtworkSurviveReopenWithoutChangingTrackIdentity() = runTest {
        val directory = temporary.newFolder()
        val store = SongDetailsStore(directory)
        store.load()
        store.edit(original, "修正的歌名", "修正的歌手")
        store.setCover(original.id, "file:///private/album.jpg")
        val effective = store.apply(original)
        assertTrue(store.saveLyrics(effective, LyricsDocument(syncedText = "[00:01]合成歌词", source = "本地导入")))
        val reopened = SongDetailsStore(directory).also { it.load() }
        assertEquals("修正的歌名", reopened.apply(original).title)
        assertEquals("修正的歌手", reopened.apply(original).artist)
        assertEquals(original.id, reopened.apply(original).id)
        assertEquals(original.sourceUrl, reopened.apply(original).sourceUrl)
        assertEquals(original.title, reopened.apply(original).sourceTitle)
        assertEquals("file:///private/album.jpg", reopened.apply(original).coverUrl)
        assertEquals("[00:01]合成歌词", reopened.details(original.id)?.lyrics?.syncedText)
    }

    @Test fun changingIdentityInvalidatesLyricsButPreservesCover() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        store.setCover(track.id, "file:///album.jpg")
        store.saveLyrics(track, LyricsDocument(plainText = "合成文字"))
        store.edit(track, "另一首歌", track.artist)
        assertNull(store.details(track.id)?.lyrics)
        assertEquals("file:///album.jpg", store.apply(track).coverUrl)
        assertFalse(store.saveLyrics(track, LyricsDocument(plainText = "迟到歌词")))
        assertNull(store.details(track.id)?.lyrics)
    }

    @Test fun unchangedIdentityKeepsLyricsAndResetCoverWorksOnAlreadyDecoratedTrack() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = MusicMetadata.normalize(original)
        val lyrics = LyricsDocument(plainText = "合成文字")
        store.saveLyrics(track, lyrics)
        store.edit(track, track.title, track.artist)
        store.setCover(track.id, "file:///album.jpg")
        val decorated = store.apply(track)
        store.setCover(track.id, "")
        assertEquals("", store.apply(decorated).coverUrl)
        assertEquals(lyrics, store.details(track.id)?.lyrics)
    }

    @Test fun clearingLyricsDoesNotDeleteAudioIdentityOrMetadata() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        store.edit(original, "歌曲", "歌手")
        store.saveLyrics(store.apply(original), LyricsDocument(plainText = "合成文字"))
        store.clearLyrics(original.id)
        assertNull(store.details(original.id)?.lyrics)
        assertEquals("歌曲", store.apply(original).title)
        assertEquals(original.cid, store.apply(original).cid)
    }

    @Test fun failedWriteDoesNotPublishSuccessOrLoseOldState() = runTest {
        val directory = temporary.newFolder()
        val store = SongDetailsStore(directory).also { it.load() }
        store.edit(original, "已保存", "歌手")
        val file = directory.listFiles()!!.single()
        file.delete()
        file.mkdir() // The final path cannot be atomically replaced with a regular file.
        assertTrue(runCatching { store.edit(original, "未保存", "歌手") }.isFailure)
        assertEquals("已保存", store.apply(original).title)
    }

    @Test fun separatePartsAndLocalFilesHaveSeparateDetails() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        store.edit(original, "第一首", "歌手")
        val other = original.copy(page = 2)
        assertNotEquals("第一首", store.apply(other).title)
        assertNull(store.details(other.id))
    }

    @Test fun invalidMetadataDoesNotWriteARecord() = runTest {
        val directory = temporary.newFolder()
        val store = SongDetailsStore(directory).also { it.load() }
        assertTrue(runCatching { store.edit(original, " ", "歌手") }.isFailure)
        assertTrue(runCatching { store.edit(original, "长".repeat(201), "歌手") }.isFailure)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}
