package app.tingyu.lyrics

import app.tingyu.model.Track
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
}
