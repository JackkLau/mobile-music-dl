package app.tingyu.bili

import app.tingyu.data.bili.*
import app.tingyu.debug.UaComparison
import app.tingyu.model.Track
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UaComparisonTest {
    private val signed = "https://a.bilivideo.com/audio.m4s?sign=private%2Fsignature"
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)
    private fun repository(onPlay: () -> Unit = {}): BilibiliRepository = BilibiliRepository({ "" }, BiliTransport { url, _ ->
        BiliResponse(200, when (url.encodedPath) {
            "/x/web-interface/view" -> """{"code":0,"data":{"bvid":"BV1xx411c7mD","title":"Music","cid":42,"duration":60}}"""
            "/x/web-interface/nav" -> """{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
            else -> { onPlay(); """{"code":0,"data":{"timelength":60000,"dash":{"audio":[{"baseUrl":"$signed","codecs":"mp4a.40.2","mimeType":"audio/mp4"}]}}}""" }
        })
    })

    @Test fun comparisonResolvesOnceAndUsesTheSameAddressForBothUas() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mp4")
                .setHeader("Content-Range", "bytes 0-23/24").setBody(Buffer().write(mp4)))
            var resolutions = 0
            val destinations = mutableListOf<String>()
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                synchronized(destinations) { destinations += chain.request().url.toString() }
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val lines = mutableListOf<String>()
            val result = UaComparison(repository { resolutions++ }, client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile(), lines::add)
            assertTrue(result.failed)
            assertFalse(result.stop)
            assertEquals(1, resolutions)
            assertEquals(listOf(signed, signed), destinations)
            val desktop = server.takeRequest()
            val mobile = server.takeRequest()
            assertEquals(UserAgentProfile.DESKTOP.value, desktop.getHeader("User-Agent"))
            assertEquals(UserAgentProfile.MOBILE.value, mobile.getHeader("User-Agent"))
            assertEquals("bytes=0-1023", desktop.getHeader("Range"))
            assertEquals(desktop.getHeader("Referer"), mobile.getHeader("Referer"))
            assertTrue(lines.any { it.contains("http=403") && it.contains("sample=not_confirmed") })
            assertTrue(lines.any { it.contains("http=206") && it.contains("sample=valid_mp4_header") })
            assertFalse(lines.any { it.contains("private") || it.contains("sign=") })
        }
    }

    @Test fun comparisonStopsOnThrottlingWithoutTryingTheOtherUa() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val result = UaComparison(repository(), client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile()) { }
            assertTrue(result.failed)
            assertTrue(result.stop)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun serverIgnoringRangeIsStillSampledAtMostOneKilobyte() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mp4")
                    .setBody(Buffer().write(mp4 + ByteArray(3000))))
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val lines = mutableListOf<String>()
            val result = UaComparison(repository(), client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile(), lines::add)
            assertFalse(result.failed)
            assertFalse(result.stop)
            val samples = lines.filter { it.contains("event=sample_result") }
            assertEquals(2, samples.size)
            assertTrue(samples.all { it.contains("bytes=1024") })
        }
    }

    @Test fun authorizationFailureStopsAndKeepsCallerCorrelation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val lines = mutableListOf<String>()
            val result = UaComparison(repository(), client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile(), "same-run", lines::add)
            assertTrue(result.failed && result.stop)
            assertEquals(1, server.requestCount)
            assertTrue(lines.all { it.contains("request=same-run") })
            assertTrue(lines.last().contains("event=stopped"))
        }
    }

    @Test fun misleadingSuccessfulHtmlIsFailureForBothUas() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody("<html>Access denied</html>")) }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val result = UaComparison(repository(), client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile()) { }
            assertTrue(result.failed)
            assertFalse(result.stop)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellationClosesTheInFlightCallWithoutSecondUaRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            val job = launch(Dispatchers.IO) {
                UaComparison(repository(), client).run(Track("BV1xx411c7mD", 42, "Music"), NetworkProfile()) { }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
