package app.tingyu.catalog

import app.tingyu.model.Track
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MusicCatalogRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repository: MusicCatalogRepository
    private val track = Track("BVtest", 1, "合成曲", "测试歌手", durationMs = 120000)
    private var now = 1_800_000_000_000L
    private val cover = "https://p2.music.126.net/synthetic/cover.jpg"

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().followRedirects(false).callTimeout(3, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("test_cookie").value("never_send").domain(url.host).build())
            }).build()
        repository = MusicCatalogRepository("test", client, server.url("/netease/"), server.url("/qq/"), { now }, 0) { server.url("/image") }
    }
    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private fun song(id: Long = 1, name: String = track.title, singer: String = track.artist,
        duration: Long = 120000, image: String = cover, artistId: Long = 10) = buildJsonObject {
        put("id", id); put("name", name); put("duration", duration)
        putJsonArray("artists") { add(buildJsonObject { put("id", artistId); put("name", singer) }) }
        putJsonObject("album") { put("id", id + 100); put("name", "合成专辑"); put("picUrl", image) }
    }
    private fun json(value: JsonElement) = MockResponse().setHeader("Content-Type", "application/json;charset=utf-8").setBody(value.toString())
    private fun search(vararg songs: JsonObject) = json(buildJsonObject { put("code", 200); putJsonObject("result") { put("songs", JsonArray(songs.toList())) } })
    private fun detail(vararg songs: JsonObject) = json(buildJsonObject { put("code", 200); put("songs", JsonArray(songs.toList())) })
    private fun lyrics(text: String = "[00:01.00]合成第一行\n[00:02.00]合成第二行", translation: String = "") = json(buildJsonObject {
        put("code", 200); putJsonObject("lrc") { put("lyric", text) }; putJsonObject("tlyric") { put("lyric", translation) }
    })
    private fun artist() = json(buildJsonObject {
        put("code", 200); putJsonObject("artist") { put("id", 10); put("name", track.artist); put("img1v1Url", "https://p1.music.126.net/synthetic/artist.jpg") }
    })
    private fun enqueueArtwork() { server.enqueue(search(song())); server.enqueue(detail(song())); server.enqueue(artist()) }

    @Test fun exactUniqueSongGetsTimedLyricsAndTranslationWithoutCredentials() = runBlocking {
        server.enqueue(search(song())); server.enqueue(detail(song())); server.enqueue(lyrics(translation = "[00:01.00]合成翻译"))
        val document = repository.lyrics(track)!!
        assertEquals("网易云音乐", document.source)
        assertEquals("https://music.163.com/song?id=1", document.sourceUrl)
        assertTrue(document.plainText.contains("合成第一行\n合成翻译"))
        assertTrue(document.syncedText.contains("[00:02.000]"))
        val first = server.takeRequest()
        assertEquals("${track.title} ${track.artist}", first.requestUrl!!.queryParameter("s"))
        assertNull(first.getHeader("Cookie")); assertNull(first.getHeader("Authorization")); assertNull(first.getHeader("Referer"))
        assertTrue(first.getHeader("User-Agent")!!.startsWith("Weile/test"))
        server.takeRequest()
        assertEquals("/netease/api/song/lyric", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun artworkSeparatesAlbumAndArtistAndRetainsSources() = runBlocking {
        enqueueArtwork()
        val result = repository.artwork(track)
        assertEquals(2, result.size)
        assertEquals("专辑封面", result[0].kind)
        assertEquals("合成专辑", result[0].album)
        assertEquals("歌手照片", result[1].kind)
        assertTrue(result.all { it.source == "网易云音乐" && it.sourceUrl.startsWith("https://music.163.com/") })
    }

    @Test fun wrongTitleArtistOrLiveVersionNeverMatches() = runBlocking {
        server.enqueue(search(song(name = "其他曲"), song(singer = "别人"), song(name = "合成曲 (Live)")))
        assertNull(repository.lyrics(track))
        assertEquals(1, server.requestCount)
    }

    @Test fun differentDurationAndAmbiguousMatchesAreNotAttached() = runBlocking {
        server.enqueue(search(song())); server.enqueue(detail(song(duration = 123001)))
        assertNull(repository.lyrics(track))
        server.enqueue(search(song(), song(2))); server.enqueue(detail(song(), song(2)))
        assertNull(repository.lyrics(track))
        assertEquals(4, server.requestCount)
    }

    @Test fun unknownDurationShowsOnlyPlainLyrics() = runBlocking {
        server.enqueue(search(song())); server.enqueue(detail(song())); server.enqueue(lyrics())
        val result = repository.lyrics(track.copy(durationMs = 0))!!
        assertTrue(result.syncedText.isEmpty())
        assertEquals("合成第一行\n合成第二行", result.plainText)
    }

    @Test fun mismatchBetweenSearchAndDetailsIsRejected() = runBlocking {
        server.enqueue(search(song())); server.enqueue(detail(song(singer = "另一歌手")))
        assertNull(repository.lyrics(track))
        assertEquals(2, server.requestCount)
    }

    @Test fun qqIsOnlyQueriedWhenNeteaseHasNoCoverAndOnlyExactSongsAreOffered() = runBlocking {
        server.enqueue(search())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-javascript;charset=utf-8").setBody(buildJsonObject {
            put("code", 0); putJsonObject("data") { putJsonObject("song") { putJsonArray("list") {
                add(buildJsonObject {
                    put("songname", track.title); put("albummid", "000TestAlbum12"); put("albumname", "测试 QQ 专辑")
                    putJsonArray("singer") { add(buildJsonObject { put("mid", "000TestArtist1"); put("name", track.artist) }) }
                })
                add(buildJsonObject { put("songname", "其他曲"); put("albummid", "000NotAlbum123") })
            } } }
        }.toString()))
        val result = repository.artwork(track)
        assertEquals(2, result.size)
        assertEquals("QQ 音乐", result.first().source)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000000TestAlbum12.jpg", result.first().imageUrl)
        assertEquals("歌手照片", result[1].kind)
        server.takeRequest()
        assertEquals("/qq/soso/fcgi-bin/client_search_cp", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun candidateImageMustHaveBeenOfferedAndMustRemainUnmodified() = runBlocking {
        enqueueArtwork()
        val candidate = repository.artwork(track).first()
        expectIo { repository.image(candidate.copy(imageUrl = "https://p1.music.126.net/other.jpg")) }
        expectIo { repository.image(candidate.copy(source = "other")) }
        assertEquals(3, server.requestCount)
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(png)))
        assertArrayEquals(png, repository.image(candidate))
    }

    @Test fun unsafeImageHostsAndCredentialsAreRejected() {
        listOf("http://p1.music.126.net:8080/a.jpg", "https://user@p1.music.126.net/a.jpg", "https://p1.music.126.net:444/a.jpg", "https://p1.music.126.net.attacker.test/a.jpg", "https://127.0.0.1/a.jpg", "https://example.com/a.jpg")
            .forEach { assertNull(it, MusicCatalogRepository.officialImage(it)) }
        assertEquals(cover, MusicCatalogRepository.officialImage(cover))
        assertEquals(cover, MusicCatalogRepository.officialImage(cover.replace("https://", "http://")))
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1")
            .forEach { assertFalse(it, MusicCatalogRepository.publicAddress(InetAddress.getByName(it))) }
        assertTrue(MusicCatalogRepository.publicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun imageHtmlMasqueradeRedirectAndLargeDeclaredBodyAreRejected() = runBlocking {
        enqueueArtwork(); val candidate = repository.artwork(track).first()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("<html>access denied</html>"))
        expectIo { repository.image(candidate) }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/private")))
        expectIo { repository.image(candidate) }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setHeader("Content-Length", 12 * 1024 * 1024 + 1))
        expectIo { repository.image(candidate) }
        assertEquals(6, server.requestCount)
    }

    @Test fun rateLimitHonorsRetryAfterWithoutRepeatingRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "10"))
        expectIo { repository.lyrics(track) }
        now += 9999
        expectIo { repository.lyrics(track) }
        assertEquals(1, server.requestCount)
        now += 1
        server.enqueue(search())
        assertNull(repository.lyrics(track))
        assertEquals(2, server.requestCount)
    }

    @Test fun htmlAndJsonpCannotBecomeCatalogData() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        expectIo { repository.lyrics(track) }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-javascript").setBody("callback({\"code\":200})"))
        expectIo { repository.lyrics(track) }
    }

    @Test fun userCanSelectAnExactIdentityVersionWithDifferentDuration() = runBlocking {
        server.enqueue(search(song(), song(2, duration = 123000)))
        server.enqueue(detail(song(), song(2, duration = 123000)))
        val candidates = repository.lyricCandidates(track)
        assertEquals(2, candidates.size)
        assertEquals(123000L, candidates[1].durationMs)
        assertEquals("合成专辑", candidates[1].album)
        expectIo { repository.lyrics(candidates[1].copy(title = "tampered")) }
        assertEquals(2, server.requestCount)
        server.enqueue(lyrics())
        val selected = repository.lyrics(candidates[1])!!
        assertEquals("https://music.163.com/song?id=2", selected.sourceUrl)
        assertTrue(selected.syncedText.isNotBlank())
    }

    @Test fun selectedUnknownDurationIsPlainAndOversizedLyricsAreRejected() = runBlocking {
        server.enqueue(search(song(duration = 0))); server.enqueue(detail(song(duration = 0)))
        val candidate = repository.lyricCandidates(track).single()
        server.enqueue(lyrics())
        assertTrue(repository.lyrics(candidate)!!.syncedText.isEmpty())
        server.enqueue(lyrics("A".repeat(256 * 1024 + 1)))
        expectIo { repository.lyrics(candidate) }
    }

    @Test fun languageOffsetsAreAppliedIndependentlyAndRealJpgAliasIsAccepted() = runBlocking {
        server.enqueue(search(song())); server.enqueue(detail(song()))
        server.enqueue(lyrics("[offset:1000]\n[00:02.00]原文合成", "[offset:0]\n[00:02.00]翻译合成"))
        val result = repository.lyrics(track)!!
        assertTrue(result.syncedText.contains("[00:01.000]原文合成"))
        assertTrue(result.syncedText.contains("[00:02.000]翻译合成"))
        enqueueArtwork()
        val candidate = repository.artwork(track).first()
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpg").setBody(okio.Buffer().write(png)))
        assertArrayEquals(png, repository.image(candidate))
    }

    @Test fun blankArtistDoesNotSendNetworkAndCancellationReleasesRepository() = runBlocking {
        expectIo { repository.lyrics(track.copy(artist = "")) }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { repository.lyrics(track) }
        repeat(100) { if (server.requestCount == 0) delay(10) }
        assertEquals(1, server.requestCount)
        pending.cancelAndJoin()
        server.enqueue(search())
        assertNull(repository.lyrics(track))
    }

    @Test fun discoveryKeepsDuplicateReleasesAndDifferentVersionsForExplicitChoice() = runBlocking {
        val original = song(1)
        val reissue = song(2)
        val live = song(3, name = "合成曲 (Live)", singer = "另一歌手", duration = 150000)
        server.enqueue(search(original, reissue, live)); server.enqueue(detail(original, reissue, live))
        val candidates = repository.lyricCandidates(track)
        assertEquals(listOf("netease:song:1", "netease:song:2", "netease:song:3"), candidates.map { it.id })
        assertEquals("合成曲 (Live)", candidates[2].title)
        assertEquals("另一歌手", candidates[2].artist)
        assertEquals(150000L, candidates[2].durationMs)
        server.enqueue(lyrics())
        assertNotNull(repository.lyrics(candidates[2]))
        server.takeRequest(); server.takeRequest()
        assertEquals("3", server.takeRequest().requestUrl!!.queryParameter("id"))
    }

    @Test fun discoveryAllowsTitleOnlyWithoutInventingArtistOrDiscardingLongIntro() = runBlocking {
        val original = song(duration = 120000)
        server.enqueue(search(original)); server.enqueue(detail(original))
        val candidates = repository.lyricCandidates(track.copy(artist = "", durationMs = 135000))
        assertEquals(track.artist, candidates.single().artist)
        assertEquals(120000L, candidates.single().durationMs)
        assertEquals(track.title, server.takeRequest().requestUrl!!.queryParameter("s"))
    }

    @Test fun titleAndArtistExactMatchesRankBeforeOtherRecordingsWithoutChangingSearchIdentity() = runBlocking {
        val first = song(1, name = "合成曲 (Live)", duration = 120000)
        val second = song(2, singer = "别人", duration = 120000)
        val exact = song(3, duration = 125000)
        server.enqueue(search(first, second, exact)); server.enqueue(detail(first, second, exact))
        val candidates = repository.lyricCandidates(track)
        assertEquals(listOf("netease:song:3", "netease:song:2", "netease:song:1"), candidates.map { it.id })
        assertEquals(track.title, candidates[0].title)
        assertEquals("别人", candidates[1].artist)
    }

    @Test fun discoveryAfterAutomaticFailureKeepsTheOriginalHttpStatusDuringCooldown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "30"))
        expectIo { repository.lyrics(track) }
        try { repository.lyricCandidates(track); fail("Expected cooldown") }
        catch (error: IOException) {
            assertTrue(error.message!!.contains("网易云音乐"))
            assertTrue(error.message!!.contains("503"))
            assertTrue(error.message!!.contains("冷却"))
        }
        assertEquals(1, server.requestCount)
    }

    private suspend fun expectIo(block: suspend () -> Any?) {
        try { block(); fail("Expected IOException") }
        catch (error: IOException) { assertFalse(error.message.orEmpty().contains("http")) }
    }
}
