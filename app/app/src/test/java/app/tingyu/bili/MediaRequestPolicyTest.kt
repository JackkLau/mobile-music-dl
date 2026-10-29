package app.tingyu.bili

import app.tingyu.data.bili.MediaRequestPolicy
import app.tingyu.data.bili.NetworkTrace
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.model.AudioStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaRequestPolicyTest {
    private val signedUrl = "https://a.bilivideo.com/audio.m4s?sign=a%2Fb%2Bc&deadline=123&x=1&x=2"
    private val source = "https://www.bilibili.com/video/BV1xx411c7mD?p=2"
    private val stream = AudioStream(signedUrl, headers = mapOf("Referer" to source,
        "Cookie" to "SESSDATA=private", "Authorization" to "secret", "X-Private" to "private"))

    @Test fun actualInitialRequestUsesDesktopAndOnlyPublicMediaHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("sample"))
            // Test-only routing changes the destination after the production request policy runs.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/sample")).build())
            }.build()
            client.newCall(MediaRequestPolicy.request(signedUrl, stream, UserAgentProfile.DESKTOP)).execute().use {
                assertEquals(206, it.code)
            }
            val sent = server.takeRequest()
            assertEquals(UserAgentProfile.DESKTOP.value, sent.getHeader("User-Agent"))
            assertEquals(source, sent.getHeader("Referer"))
            assertEquals("https://www.bilibili.com", sent.getHeader("Origin"))
            assertEquals("bytes=0-", sent.getHeader("Range"))
            assertEquals("identity", sent.getHeader("Accept-Encoding"))
            assertNull(sent.getHeader("If-Range"))
            assertNull(sent.getHeader("Cookie"))
            assertNull(sent.getHeader("Authorization"))
            assertNull(sent.getHeader("X-Private"))
        }
    }

    @Test fun resumeRequiresStrongEntityAndRetainsByteOffset() {
        val request = MediaRequestPolicy.request(signedUrl, stream, UserAgentProfile.DESKTOP, 512, "\"entity\"")
        assertEquals("bytes=512-", request.header("Range"))
        assertEquals("\"entity\"", request.header("If-Range"))
        for (etag in listOf(null, "W/\"weak\"", "unquoted")) {
            try { MediaRequestPolicy.request(signedUrl, stream, UserAgentProfile.DESKTOP, 512, etag); fail("Unsafe resume") }
            catch (_: IOException) { }
        }
    }

    @Test fun changingOnlyUaPreservesSignedAddressAndEveryOtherHeader() {
        val desktop = MediaRequestPolicy.request(signedUrl, stream, UserAgentProfile.DESKTOP, sampleBytes = 1024)
        val mobile = MediaRequestPolicy.request(signedUrl, stream, UserAgentProfile.MOBILE, sampleBytes = 1024)
        assertEquals(signedUrl, desktop.url.toString())
        assertEquals(desktop.url, mobile.url)
        assertEquals("bytes=0-1023", mobile.header("Range"))
        val withoutUa: (okhttp3.Request) -> Map<String, List<String>> = { it.headers.toMultimap().filterKeys { key -> !key.equals("User-Agent", true) } }
        assertEquals(withoutUa(desktop), withoutUa(mobile))
        assertNotEquals(desktop.header("User-Agent"), mobile.header("User-Agent"))
    }

    @Test fun invalidSourcesCannotSetOriginOrLeakCredentials() {
        listOf("https://www.bilibili.com.evil.test/video/BV1xx411c7mD", "https://user:pass@www.bilibili.com/video/BV1xx411c7mD", "https://www.bilibili.com:8443/video/BV1xx411c7mD", "http://www.bilibili.com/video/BV1xx411c7mD").forEach { ref ->
            try { MediaRequestPolicy.request(signedUrl, stream.copy(headers = mapOf("Referer" to ref)), UserAgentProfile.DESKTOP); fail("Accepted invalid source") }
            catch (_: IOException) { }
        }
    }

    @Test fun redirectsAreSubjectToSameDestinationPolicy() {
        listOf("https://127.0.0.1/a", "https://evil.example/a", "https://bilivideo.com.evil.example/a").forEach { next ->
            try { MediaRequestPolicy.request(next, stream, UserAgentProfile.DESKTOP); fail("Accepted invalid target") }
            catch (_: IOException) { }
        }
    }

    @Test fun successfulHttpIsNotEnoughToConfirmAudioSample() {
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)
        assertTrue(MediaRequestPolicy.validSample(206, "audio/mp4", "bytes 0-23/50000", mp4))
        assertTrue(MediaRequestPolicy.validSample(200, "application/octet-stream", null, mp4))
        assertFalse(MediaRequestPolicy.validSample(200, "text/html", null, mp4))
        assertFalse(MediaRequestPolicy.validSample(200, "audio/mp4", null, "<html>Access denied</html>".toByteArray()))
        assertFalse(MediaRequestPolicy.validSample(206, "audio/mp4", "bytes 24-47/50000", mp4))
        assertFalse(MediaRequestPolicy.validSample(403, "audio/mp4", null, mp4))
        assertFalse(MediaRequestPolicy.validSample(200, "audio/mp4", null, byteArrayOf(0, 0, 0, 8) + "ftyp".toByteArray()))
        assertFalse(MediaRequestPolicy.validSample(200, "audio/mp4", null, byteArrayOf(0, 0, 0, 4) + "ftypisom".toByteArray() + ByteArray(4)))
    }

    @Test fun logsContainDigestInsteadOfSignedUrlOrAccountData() {
        val line = NetworkTrace.line("request-123", mapOf("stage" to "cdn", "http" to "403", "cookie" to "SESSDATA=private", "failure" to "https://sensitive.example/?token=secret"), signedUrl)
        assertTrue(line.contains("http=403"))
        assertTrue(line.contains("host=a.bilivideo.com"))
        assertTrue(line.contains("url_id="))
        assertFalse(line.contains("sign="))
        assertFalse(line.contains("deadline"))
        assertFalse(line.contains("SESSDATA"))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("https://"))
        assertEquals(NetworkTrace.urlId(signedUrl), NetworkTrace.urlId(signedUrl))
        assertNotEquals(NetworkTrace.urlId(signedUrl), NetworkTrace.urlId("${signedUrl}3"))
    }
}
