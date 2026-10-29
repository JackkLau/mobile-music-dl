package app.tingyu.lyrics

import app.tingyu.model.Track
import app.tingyu.catalog.LyricCandidate
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricsLookupTest {
    private val track = Track("BV1test000001", 42, "测试歌", "测试歌手", durationMs = 120000)
    @Test fun acceptedPrimaryDoesNotSendExtraRequest() = runBlocking {
        val expected = LyricsDocument(plainText = "合成测试", source = "主来源")
        assertEquals(expected, LyricsLookup({ expected }, { error("Must not query fallback") }).find(track))
    }
    @Test fun unavailablePrimaryFallsBackAndPreservesProvenance() = runBlocking {
        val expected = LyricsDocument(plainText = "合成测试", source = "备用来源")
        assertEquals(expected, LyricsLookup({ throw IOException("temporary") }, { expected }).find(track))
    }
    @Test fun noMatchesRemainDistinctFromUnavailableProviders() = runBlocking {
        assertNull(LyricsLookup({ null }, { null }).find(track))
        try { LyricsLookup({ null }, { throw IOException("private details") }).find(track); fail("Expected failure") }
        catch (error: IOException) { assertFalse(error.message!!.contains("private details")) }
    }
    @Test fun cancellationNeverQueriesAnotherProvider() = runBlocking {
        try { LyricsLookup({ throw CancellationException() }, { error("Must not query fallback") }).find(track); fail("Expected cancel") }
        catch (_: CancellationException) { }
    }
    @Test fun incompleteSignatureDoesNotQueryProviders() = runBlocking {
        try { LyricsLookup({ error("No query") }, { error("No query") }).find(track.copy(artist = "")); fail("Expected failure") }
        catch (_: IOException) { }
    }
    private val candidate = LyricCandidate("netease:song:1", "测试歌", "测试歌手", "专辑", 120000, "网易云音乐", "https://music.163.com/song?id=1")

    @Test fun discoveryKeepsWorkingProviderWhenOtherServiceIsBusy() = runBlocking {
        val lookup = LyricsLookup({ null }, { null }, { listOf(candidate) },
            { throw LyricsSourceException("LRCLIB", "请求", "服务暂时繁忙", httpStatus = 503) })
        val result = lookup.candidates(track.copy(artist = ""))
        assertEquals(listOf(candidate), result.candidates)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("LRCLIB"))
        assertTrue(result.warnings.single().contains("503"))
    }

    @Test fun failedDiscoveryAndEmptyCatalogRemainDifferentStates() = runBlocking {
        val empty = LyricsLookup({ null }, { null }).candidates(track)
        assertTrue(empty.candidates.isEmpty()); assertTrue(empty.warnings.isEmpty())
        val failed = LyricsLookup({ null }, { null }, { throw IOException("private URL") },
            { throw LyricsSourceException("LRCLIB", "请求", "服务暂时繁忙", httpStatus = 503) }).candidates(track)
        assertTrue(failed.candidates.isEmpty()); assertEquals(2, failed.warnings.size)
        assertTrue(failed.warnings.none { it.contains("private URL") })
    }

    @Test fun cancelledDiscoveryDoesNotCallAnotherProvider() = runBlocking {
        try {
            LyricsLookup({ null }, { null }, { throw CancellationException() }, { error("Must not query fallback") }).candidates(track)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun selectionRoutesOnlyToItsProvider() = runBlocking {
        val expected = LyricsDocument(plainText = "合成歌词", source = "网易云音乐")
        val lookup = LyricsLookup({ null }, { null }, primarySelected = { assertEquals(candidate, it); expected },
            fallbackSelected = { error("Must not query other provider") })
        assertEquals(expected, lookup.select(candidate))
        try { lookup.select(candidate.copy(id = "unknown:1")); fail("Expected rejection") } catch (_: IOException) { }
    }

}
