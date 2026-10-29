package app.tingyu.bili

import app.tingyu.data.bili.InvalidMediaResponseException
import app.tingyu.data.bili.MediaResponsePolicy
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class MediaResponsePolicyTest {
    private val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)

    @Test fun validMp4IsAcceptedWithoutConsumingDownloadBytes() {
        listOf("audio/mp4", "application/octet-stream", null).forEach { type ->
            withResponse(mediaResponse(mp4).apply { if (type != null) setHeader("Content-Type", type) }) { response ->
                assertEquals(mp4.size.toLong(), MediaResponsePolicy.validate(response, 0))
                assertArrayEquals(mp4, response.body!!.bytes())
            }
        }
    }

    @Test fun missingLengthAndTypeCanBeValidatedByTheRealMp4Header() {
        withResponse(MockResponse().setChunkedBody(Buffer().write(mp4), 5)) { response ->
            assertEquals(-1L, MediaResponsePolicy.validate(response, 0))
            assertArrayEquals(mp4, response.body!!.bytes())
        }
    }

    @Test fun initialRangeReturnsCompleteEntityLength() {
        withResponse(mediaResponse(mp4).setResponseCode(206).setHeader("Content-Range", "bytes 0-23/1000")) { response ->
            assertEquals(1000L, MediaResponsePolicy.validate(response, 0))
            assertArrayEquals(mp4, response.body!!.bytes())
        }
    }

    @Test fun mislabeledHtmlIsRejectedForSuccessfulFullAndRangeResponses() {
        val html = "<html>Access denied: PRIVATE_SERVER_BODY</html>".toByteArray()
        for (status in listOf(200, 206)) {
            for (type in listOf("audio/mp4", "application/octet-stream", null)) {
                val reply = mediaResponse(html).setResponseCode(status)
                if (type != null) reply.setHeader("Content-Type", type)
                if (status == 206) reply.setHeader("Content-Range", "bytes 0-${html.size - 1}/${html.size}")
                assertRejected(reply, 0, "content_header")
            }
        }
    }

    @Test fun explicitErrorContentTypesAreRejectedEvenWithAnMp4Prefix() {
        listOf("text/html; charset=UTF-8", "application/json", "application/problem+json", "application/xhtml+xml").forEach { type ->
            assertRejected(mediaResponse(mp4).setHeader("Content-Type", type), 0, "content_type")
        }
    }

    @Test fun nonzeroResumeDoesNotRequireAnotherMp4Header() {
        val fragment = ByteArray(24) { 0x35 }
        withResponse(mediaResponse(fragment).setResponseCode(206).setHeader("Content-Range", "bytes 512-535/1000")) { response ->
            assertEquals(1000L, MediaResponsePolicy.validate(response, 512))
            assertArrayEquals(fragment, response.body!!.bytes())
        }
    }

    @Test fun nonzeroResumeCannotAppendAFullResponse() {
        assertRejected(mediaResponse(mp4), 512, "range")
    }

    @Test fun malformedOrWrongStartingRangesAreRejected() {
        listOf(null, "bytes 1-24/1000", "bytes 0-23/*", "bytes 0-23/23", "bytes -1-22/1000").forEach { range ->
            assertRejected(mediaResponse(mp4).setResponseCode(206).apply {
                if (range != null) setHeader("Content-Range", range)
            }, 0, "range")
        }
        assertRejected(mediaResponse(mp4).setResponseCode(206).setHeader("Content-Range", "bytes 0-23/1000"), 512, "range")
    }

    @Test fun rangeLengthMustMatchAdvertisedContentLength() {
        assertRejected(mediaResponse(mp4).setResponseCode(206).setHeader("Content-Range", "bytes 0-22/1000"), 0, "length")
    }

    @Test fun onlySuccessfulAudioResponseCodesAreAccepted() {
        listOf(204, 302, 401, 403, 404, 410, 429, 500).forEach { status ->
            assertRejected(MockResponse().setResponseCode(status), 0, "http_status")
        }
    }

    @Test fun emptyOrTruncatedMp4HeaderIsRejected() {
        listOf(byteArrayOf(), byteArrayOf(0, 0, 0, 8) + "ftyp".toByteArray()).forEach { bytes ->
            assertRejected(mediaResponse(bytes), 0, "content_header")
        }
    }

    private fun mediaResponse(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))

    private fun assertRejected(reply: MockResponse, offset: Long, reason: String) {
        withResponse(reply) { response ->
            try {
                MediaResponsePolicy.validate(response, offset)
                fail("Expected $reason")
            } catch (error: InvalidMediaResponseException) {
                assertEquals(reason, error.reason)
                assertFalse(error.message.orEmpty().contains("PRIVATE_SERVER_BODY"))
                assertFalse(error.message.orEmpty().contains("http://"))
            }
        }
    }

    private fun withResponse(reply: MockResponse, check: (Response) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(reply)
            OkHttpClient().newCall(okhttp3.Request.Builder().url(server.url("/audio")).build()).execute().use(check)
        }
    }
}
