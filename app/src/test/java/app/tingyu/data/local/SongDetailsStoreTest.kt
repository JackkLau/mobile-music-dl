package app.tingyu.data.local

import app.tingyu.lyrics.LyricsDocument
import app.tingyu.model.MusicMetadata
import app.tingyu.model.Track
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    @Test fun calibrationSurvivesReopenClampsAndCanBeReset() = runTest {
        val directory = temporary.newFolder()
        val store = SongDetailsStore(directory).also { it.load() }
        val track = store.apply(original)
        assertFalse(store.adjustLyricsOffset(track, 500))
        assertTrue(directory.listFiles().isNullOrEmpty())
        store.saveLyrics(track, LyricsDocument(syncedText = "[00:01]合成歌词"))
        store.adjustLyricsOffset(track, 500)
        val reopened = SongDetailsStore(directory).also { it.load() }
        assertEquals(500L, reopened.lyricsFor(track)?.offsetMs)
        reopened.adjustLyricsOffset(track, Long.MAX_VALUE)
        assertEquals(30_000L, reopened.lyricsFor(track)?.offsetMs)
        reopened.adjustLyricsOffset(track, Long.MIN_VALUE)
        assertEquals(-30_000L, reopened.lyricsFor(track)?.offsetMs)
        reopened.resetLyricsOffset(track)
        assertEquals(0L, reopened.lyricsFor(track)?.offsetMs)
    }

    @Test fun simultaneousCalibrationStepsDoNotLoseUpdates() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        store.saveLyrics(track, LyricsDocument(syncedText = "[00:01]合成歌词"))
        (1..8).map { async { store.adjustLyricsOffset(track, 500) } }.awaitAll()
        assertEquals(4000L, store.lyricsFor(track)?.offsetMs)
    }

    @Test fun sameLyricsKeepCalibrationButReplacementAndClearResetIt() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        val document = LyricsDocument(syncedText = "[00:01]合成歌词", source = "本地导入")
        store.saveLyrics(track, document)
        store.adjustLyricsOffset(track, -500)
        store.saveLyrics(track, document)
        assertEquals(-500L, store.lyricsFor(track)?.offsetMs)
        store.saveLyrics(track, document.copy(syncedText = "[00:02]另一行合成歌词"))
        assertEquals(0L, store.lyricsFor(track)?.offsetMs)
        store.adjustLyricsOffset(track, 500)
        store.clearLyrics(track.id)
        assertFalse(store.resetLyricsOffset(track))
        store.saveLyrics(track, document)
        assertEquals(0L, store.lyricsFor(track)?.offsetMs)
    }

    @Test fun cacheRejectsChangedSongContentAndKnownDuration() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        store.saveLyrics(track, LyricsDocument(plainText = "合成歌词"))
        assertNotNull(store.lyricsFor(track))
        assertNull(store.lyricsFor(track.copy(title = "另一首歌")))
        assertNull(store.lyricsFor(track.copy(artist = "另一位歌手")))
        assertNull(store.lyricsFor(track.copy(cid = track.cid + 1)))
        assertNull(store.lyricsFor(track.copy(durationMs = track.durationMs + 2001)))
        assertNotNull(store.lyricsFor(track.copy(durationMs = track.durationMs + 2000)))
        assertNotNull(store.lyricsFor(track.copy(cid = 0, durationMs = 0)))
        assertFalse(store.adjustLyricsOffset(track.copy(cid = track.cid + 1), 500))
        assertEquals(0L, store.lyricsFor(track)?.offsetMs)
    }

    @Test fun staleCalibrationAfterMetadataEditCannotResurrectOldLyrics() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        store.saveLyrics(track, LyricsDocument(plainText = "旧合成歌词"))
        store.edit(track, "新歌名", "新歌手")
        val current = store.apply(track)
        store.saveLyrics(current, LyricsDocument(plainText = "新合成歌词"))
        assertFalse(store.adjustLyricsOffset(track, 500))
        assertEquals(0L, store.lyricsFor(current)?.offsetMs)
    }

    @Test fun legacyLyricsRemainReadableAndCalibrationAddsIdentity() = runTest {
        val directory = temporary.newFolder()
        File(directory, "${SongDetailsStore.key(original.id)}.json").writeText(
            """{"id":"${original.id}","lyrics":{"plainText":"旧版合成歌词","source":"本地导入"}}""")
        val store = SongDetailsStore(directory).also { it.load() }
        val track = store.apply(original)
        assertNull(store.details(track.id)?.lyricsIdentity)
        assertEquals(0L, store.lyricsFor(track)?.offsetMs)
        assertEquals("旧版合成歌词", store.lyricsFor(track)?.plainText)
        store.adjustLyricsOffset(track, 500)
        val reopened = SongDetailsStore(directory).also { it.load() }
        assertNotNull(reopened.details(track.id)?.lyricsIdentity)
        assertEquals(500L, reopened.lyricsFor(track)?.offsetMs)
        assertNull(reopened.lyricsFor(track.copy(title = "另一首歌")))
    }

    @Test fun matchedCoverRequiresCurrentSongAndMetadataEditClearsOnlyOnlineCover() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        assertTrue(store.saveMatchedCover(track, "file:///private/online.jpg", "曲库", "https://example.com/album"))
        assertEquals("曲库", store.details(track.id)?.coverSource)
        val decorated = store.apply(track)
        store.edit(track, "新歌名", track.artist)
        assertEquals("", store.apply(decorated).coverUrl)
        assertEquals("", store.details(track.id)?.coverSourceUrl)
        assertNull(store.details(track.id)?.coverIdentity)
        assertFalse(store.saveMatchedCover(track, "file:///private/late.jpg", "曲库", "https://example.com/album"))
        store.setCover(track.id, "file:///private/manual.jpg")
        store.edit(store.apply(track), "又一歌名", track.artist)
        assertEquals("file:///private/manual.jpg", store.apply(track).coverUrl)
        assertEquals("", store.details(track.id)?.coverSource)
    }

    @Test fun matchedCoverCannotCommitRemoteOrMissingSource() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        assertTrue(runCatching { store.saveMatchedCover(track, "https://example.com/cover.jpg", "曲库", "https://example.com") }.isFailure)
        assertTrue(runCatching { store.saveMatchedCover(track, "file:///private/cover.jpg", "", "https://example.com") }.isFailure)
        assertTrue(runCatching { store.saveMatchedCover(track, "file:///private/cover.jpg", "曲库", "http://example.com") }.isFailure)
        assertNull(store.details(track.id))
    }

    @Test fun matchedCoverRejectsReparsedIdentityButNotDifferentAudioDuration() = runTest {
        val directory = temporary.newFolder()
        val store = SongDetailsStore(directory).also { it.load() }
        val track = store.apply(original)
        val image = "file:///private/online.jpg"
        store.saveMatchedCover(track, image, "曲库", "https://example.com/album")
        val decorated = store.apply(track)
        val reopened = SongDetailsStore(directory).also { it.load() }
        assertEquals(image, reopened.apply(decorated).coverUrl)
        assertEquals("", reopened.apply(decorated.copy(title = "另一个歌名")).coverUrl)
        assertEquals("", reopened.apply(decorated.copy(artist = "另一位歌手")).coverUrl)
        assertEquals(image, reopened.apply(decorated.copy(cid = 999, durationMs = track.durationMs + 5000)).coverUrl)
    }

    @Test fun manualCoverClearsOnlineIdentityAndSurvivesReparsedSongName() = runTest {
        val store = SongDetailsStore(temporary.newFolder()).also { it.load() }
        val track = store.apply(original)
        store.saveMatchedCover(track, "file:///private/online.jpg", "曲库", "https://example.com/album")
        assertNotNull(store.details(track.id)?.coverIdentity)
        store.setCover(track.id, "file:///private/manual.jpg")
        assertNull(store.details(track.id)?.coverIdentity)
        assertEquals("", store.details(track.id)?.coverSource)
        assertEquals("file:///private/manual.jpg", store.apply(track.copy(title = "新歌名")).coverUrl)
    }

    @Test fun legacyCoverWithoutSignatureRemainsReadable() = runTest {
        val directory = temporary.newFolder()
        File(directory, "${SongDetailsStore.key(original.id)}.json").writeText(
            """{"id":"${original.id}","coverUri":"file:///private/legacy.jpg","coverSource":"旧来源","coverSourceUrl":"https://example.com/album"}""")
        val store = SongDetailsStore(directory).also { it.load() }
        assertNull(store.details(original.id)?.coverIdentity)
        assertEquals("file:///private/legacy.jpg", store.apply(original).coverUrl)
    }
}
