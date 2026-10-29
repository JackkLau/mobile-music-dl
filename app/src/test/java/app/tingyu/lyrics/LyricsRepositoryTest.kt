package app.tingyu.lyrics

import app.tingyu.model.Track
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LyricsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repository: LyricsRepository
    private val track = Track("BV1234567890", 1, "合成曲", "合成歌手", durationMs = 120_000)
    private var now = 1_800_000_000_000L

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
        repository = LyricsRepository("test", client, server.url("/"), { now }, { 100 })
    }
    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private fun record(title: String = track.title, artist: String = track.artist, duration: Double = 120.0,
        plain: String = "测试第一行\n测试第二行", synced: String = "[00:01.00]测试第一行\n[00:02.00]测试第二行", instrumental: Boolean = false): String =
        buildJsonObject {
            put("id", 42); put("trackName", title); put("artistName", artist); put("duration", duration)
            put("plainLyrics", plain); put("syncedLyrics", synced); put("instrumental", instrumental)
        }.toString()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    @Test fun exactLookupIdentifiesClientAndSendsOnlySongSignature() = runBlocking {
        server.enqueue(json(record()))
        val result = repository.find(track)!!
        assertTrue(result.syncedText.isNotBlank())
        assertEquals("LRCLIB", result.source)
        assertEquals("https://lrclib.net/api/get/42", result.sourceUrl)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/get", request.requestUrl!!.encodedPath)
        assertEquals(track.title, request.requestUrl!!.queryParameter("track_name"))
        assertEquals(track.artist, request.requestUrl!!.queryParameter("artist_name"))
        assertEquals("120.0", request.requestUrl!!.queryParameter("duration"))
        assertEquals("Weile/test (https://github.com/JackkLau/mobile-music-dl)", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Referer"))
    }

    @Test fun plainLyricsAndInstrumentalAreDistinct() = runBlocking {
        server.enqueue(json(record(synced = "")))
        assertEquals("测试第一行\n测试第二行", repository.find(track)!!.plainText)
        server.enqueue(json(record(plain = "", synced = "", instrumental = true)))
        val instrumental = repository.find(track)!!
        assertTrue(instrumental.instrumental)
        assertTrue(instrumental.plainText.isBlank())
    }

    @Test fun noKnownDurationDoesNotClaimSynchronizedLyrics() = runBlocking {
        server.enqueue(json(record(plain = "")))
        val result = repository.find(track.copy(durationMs = 0))!!
        assertTrue(result.syncedText.isEmpty())
        assertEquals("测试第一行\n测试第二行", result.plainText)
        assertNull(server.takeRequest().requestUrl!!.queryParameter("duration"))
    }

    @Test fun emptyTimestampLinesAreNotReportedAsFoundLyrics() = runBlocking {
        server.enqueue(json(record(plain = "", synced = "[00:00]\n[00:01]   \n[00:02]", instrumental = false)))
        assertNull(repository.find(track))
        server.enqueue(json(record(plain = "", synced = "[00:00]\n[00:01]", instrumental = true)))
        assertTrue(repository.find(track)!!.instrumental)
        server.enqueue(json(record(plain = "", synced = "[00:00]\n[00:01]合成歌词\n[00:02]")))
        assertNotNull(repository.find(track))
    }

    @Test fun missingArtistRequiresUserCorrectionWithoutNetwork() = runBlocking {
        expectIo { repository.find(track.copy(artist = "")) }
        assertEquals(0, server.requestCount)
    }

    @Test fun normalizesUnicodeCaseAndSpacingWithoutDiscardingVersionLabels() = runBlocking {
        val latin = track.copy(title = "Test Song (Live)", artist = "Demo Singer")
        server.enqueue(json(record(title = "Ｔｅｓｔ  Song (Live)", artist = "demo\u00a0singer")))
        assertNotNull(repository.find(latin))
        server.enqueue(json(record(title = "Test Song", artist = "Demo Singer")))
        assertNull(repository.find(latin))
    }

    @Test fun mismatchedIdentityOrVersionDurationIsNotAccepted() = runBlocking {
        server.enqueue(json(record(title = "其他曲")))
        assertNull(repository.find(track))
        server.enqueue(json(record(artist = "其他歌手")))
        assertNull(repository.find(track))
        server.enqueue(json(record(duration = 125.0)))
        assertNull(repository.find(track))
        server.enqueue(json(record(duration = 122.0)))
        assertNotNull(repository.find(track))
    }

    @Test fun notFoundIsNormalAndErrorPagesAreNotLyrics() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(repository.find(track))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        expectIo { repository.find(track) }
        server.enqueue(json("not JSON"))
        expectIo { repository.find(track) }
    }

    @Test fun rateLimitHonorsRetryAfterAndJitterWithoutRepeatingRequests() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        expectIo { repository.find(track) }
        expectIo { repository.find(track) }
        assertEquals(1, server.requestCount)
        now += 30_000
        expectIo { repository.find(track) }
        assertEquals(1, server.requestCount)
        now += 101
        server.enqueue(json(record()))
        assertNotNull(repository.find(track))
        assertEquals(2, server.requestCount)
    }

    @Test fun busyServerAlsoHonorsRetryAfterHttpDate() = runBlocking {
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 5000).atZone(java.time.ZoneOffset.UTC))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", date))
        expectIo { repository.find(track) }
        now += 4999
        expectIo { repository.find(track) }
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectAndOversizedBodyAreRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        expectIo { repository.find(track) }
        assertEquals(1, server.requestCount)
        server.enqueue(json("x".repeat(512 * 1024 + 1)))
        expectIo { repository.find(track) }
    }

    @Test fun excessivePlainLyricLinesAreRejected() = runBlocking {
        server.enqueue(json(record(plain = "测试\n".repeat(LrcParser.MAX_LINES + 1), synced = "")))
        expectIo { repository.find(track) }
    }

    @Test fun cancellationStopsActiveRequestAndReleasesNextLookup() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { repository.find(track) }
        repeat(100) { if (server.requestCount == 0) delay(10) }
        assertEquals(1, server.requestCount)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        server.enqueue(json(record()))
        assertNotNull(repository.find(track))
    }

    @Test fun searchKeepsVersionMetadataAndDoesNotRequireArtistOrMatchingDuration() = runBlocking {
        server.enqueue(json("[" + record(title = "合成曲 (Live)", artist = "另一歌手", duration = 150.0) + "]"))
        val result = repository.candidates(track.copy(artist = ""))
        assertEquals("合成曲 (Live)", result.single().title)
        assertEquals("另一歌手", result.single().artist)
        assertEquals(150000L, result.single().durationMs)
        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl!!.encodedPath)
        assertEquals(track.title, request.requestUrl!!.queryParameter("track_name"))
        assertNull(request.requestUrl!!.queryParameter("artist_name"))
        assertNull(request.requestUrl!!.queryParameter("duration"))
    }

    @Test fun explicitSelectionUsesOfferedIdAndRejectsForgedOrChangedResponse() = runBlocking {
        server.enqueue(json("[" + record() + "]"))
        val candidate = repository.candidates(track).single()
        expectIo { repository.lyrics(candidate.copy(id = "lrclib:song:99")) }
        expectIo { repository.lyrics(candidate.copy(title = "tampered")) }
        assertEquals(1, server.requestCount)
        server.enqueue(json(record()))
        assertTrue(repository.lyrics(candidate)!!.syncedText.isNotBlank())
        server.takeRequest()
        val selected = server.takeRequest()
        assertEquals("/api/get/42", selected.requestUrl!!.encodedPath)
        assertNull(selected.getHeader("Cookie")); assertNull(selected.getHeader("Authorization"))
        server.enqueue(json(record(title = "不同录音")))
        expectIo { repository.lyrics(candidate) }
    }

    @Test fun searchFailureRetainsStatusAndCooldownSharedWithAutomaticLookup() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "10"))
        try { repository.candidates(track); fail("Expected failure") }
        catch (error: LyricsSourceException) {
            assertTrue(error.message!!.contains("LRCLIB")); assertTrue(error.message!!.contains("503"))
        }
        try { repository.find(track); fail("Expected cooldown") }
        catch (error: LyricsSourceException) {
            assertTrue(error.message!!.contains("503")); assertTrue(error.message!!.contains("冷却"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun searchRejectsWrongShapeAndOversizedResponse() = runBlocking {
        server.enqueue(json(record()))
        expectIo { repository.candidates(track) }
        server.enqueue(json("x".repeat(512 * 1024 + 1)))
        expectIo { repository.candidates(track) }
    }

    @Test fun selectedHtmlInJsonIsNotSavedAsLyrics() = runBlocking {
        server.enqueue(json("[" + record() + "]"))
        val candidate = repository.candidates(track).single()
        server.enqueue(json(record(plain = "<html>service error</html>", synced = "")))
        expectIo { repository.lyrics(candidate) }
    }

    @Test fun cancellationStopsCandidateSearchAndAllowsNextRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { repository.candidates(track) }
        repeat(100) { if (server.requestCount == 0) delay(10) }
        assertEquals(1, server.requestCount)
        pending.cancelAndJoin()
        server.enqueue(json("[]"))
        assertTrue(repository.candidates(track).isEmpty())
    }

    private suspend fun expectIo(block: suspend () -> Any?) {
        try { block(); fail("Expected safe IOException") }
        catch (expected: IOException) {
            assertFalse(expected.message.orEmpty().contains("http://"))
        }
    }
}
