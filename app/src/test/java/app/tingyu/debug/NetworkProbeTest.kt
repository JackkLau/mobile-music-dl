package app.tingyu.debug

import app.tingyu.data.bili.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkProbeTest {
    private val video = """{"code":0,"data":{"bvid":"BV1xx411c7mD","title":"Music","cid":42,"duration":60}}"""
    private val nav = """{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)

    @Test fun sharedRunnerComparesBothAudioUasForEachParseUa() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "audio/mp4").setHeader("Content-Range", "bytes 0-23/24")
                .setBody(Buffer().write(mp4))) }
            val resolved = mutableListOf<UserAgentProfile>()
            val destinations = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                destinations += chain.request().url.toString()
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val lines = mutableListOf<String>()
            val probe = NetworkProbe({ profile, sink ->
                BilibiliRepository({ "" }, BiliTransport { url, headers ->
                    assertEquals(profile.parseUa.value, headers["User-Agent"])
                    BiliResponse(200, when (url.encodedPath) {
                        "/x/web-interface/view" -> video
                        "/x/web-interface/nav" -> nav
                        else -> {
                            resolved += profile.parseUa
                            """{"code":0,"data":{"timelength":60000,"dash":{"audio":[{"baseUrl":"https://a.bilivideo.com/${profile.parseUa.id}?sign=PRIVATE","codecs":"mp4a.40.2","mimeType":"audio/mp4"}]}}}"""
                        }
                    })
                }, profileProvider = { profile }, logSink = sink)
            }, { UaComparison(it, client) })
            assertTrue(probe.run("https://m.bilibili.com/video/BV1xx411c7mD?p=1", lines::add))
            assertEquals(listOf(UserAgentProfile.DESKTOP, UserAgentProfile.MOBILE), resolved)
            assertEquals(4, destinations.size)
            assertEquals(destinations[0], destinations[1])
            assertEquals(destinations[2], destinations[3])
            assertNotEquals(destinations[0], destinations[2])
            val requests = List(4) { server.takeRequest() }
            assertEquals(listOf(DESKTOP_UA, MOBILE_UA, DESKTOP_UA, MOBILE_UA), requests.map { it.getHeader("User-Agent") })
            assertTrue(requests.all { it.getHeader("Range") == "bytes=0-1023" && it.getHeader("Cookie") == null })
            assertEquals(4, lines.count { "event=sample_result" in it })
            assertFalse(lines.any { "PRIVATE" in it || "https://" in it })
            assertEquals(2, lines.filter { "event=sample_result" in it }.map { it.substringBefore(' ') }.distinct().size)
        }
    }

    @Test fun authorizationFailureStopsBeforeSecondParseUaOrCdn() = runBlocking {
        for (response in listOf(BiliResponse(401, "private response"), BiliResponse(200, """{"code":-101}"""))) {
            var requests = 0
            val lines = mutableListOf<String>()
            val probe = NetworkProbe({ profile, sink ->
                BilibiliRepository({ "" }, BiliTransport { _, _ -> requests++; response },
                    profileProvider = { profile }, logSink = sink)
            }, { error("Audio comparison must not start") })
            assertFalse(probe.run("BV1xx411c7mD", lines::add))
            assertEquals(1, requests)
            assertTrue(lines.any { "event=stopped" in it })
            assertFalse(lines.any { "private response" in it })
        }
    }

    @Test fun cdnThrottlingStopsBothRemainingAudioAndParseUaRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            var profiles = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val probe = NetworkProbe({ profile, sink ->
                profiles++
                BilibiliRepository({ "" }, BiliTransport { url, _ ->
                    BiliResponse(200, when (url.encodedPath) {
                        "/x/web-interface/view" -> video
                        "/x/web-interface/nav" -> nav
                        else -> """{"code":0,"data":{"timelength":60000,"dash":{"audio":[{"baseUrl":"https://a.bilivideo.com/audio","codecs":"mp4a.40.2","mimeType":"audio/mp4"}]}}}"""
                    })
                }, profileProvider = { profile }, logSink = sink)
            }, { UaComparison(it, client) })
            assertFalse(probe.run("BV1xx411c7mD") { })
            assertEquals(1, profiles)
            assertEquals(1, server.requestCount)
        }
    }
}
