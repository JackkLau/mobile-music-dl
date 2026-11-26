package app.tingyu.auth

import app.tingyu.data.auth.QrLoginClient
import app.tingyu.data.auth.QrLoginException
import app.tingyu.data.auth.QrLoginStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class QrLoginClientTest {
    private val key = "a".repeat(32)
    private val scan = "https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=$key"
    private fun generated(url: String = scan) = """{"code":0,"data":{"qrcode_key":"$key","url":"$url"}}"""
    private fun poll(code: Int) = """{"code":0,"data":{"code":$code,"refresh_token":"SECRET"}}"""

    private fun reply(request: Request, body: String, status: Int = 200, cookies: List<String> = emptyList()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody()).apply { cookies.forEach { addHeader("Set-Cookie", it) } }.build()

    private fun client(handler: (Request) -> Response) = QrLoginClient(OkHttpClient.Builder()
        .addInterceptor { chain -> handler(chain.request()) }.build())

    @Test fun onlyConfirmedPassportResponseSuppliesCookiesAndNavVerifiesTheAccount() = runBlocking {
        val states = listOf(86101, 86090, 0).iterator()
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            when (request.url.encodedPath) {
                "/x/passport-login/web/qrcode/generate" -> reply(request, generated())
                "/x/passport-login/web/qrcode/poll" -> reply(request, poll(states.next()), cookies = listOf(
                    "SESSDATA=t%2C1; Domain=.bilibili.com; Path=/; Secure; HttpOnly",
                    "bili_jct=csrf; Domain=bilibili.com; Path=/",
                    "unsupported=skip; Domain=bilibili.com; Path=/",
                    "sid=passport-only; Path=/",
                    "buvid3=expired; Domain=bilibili.com; Path=/; Max-Age=0",
                    "buvid4=other-path; Domain=bilibili.com; Path=/never",
                ))
                "/x/web-interface/nav" -> reply(request, """{"code":0,"data":{"isLogin":true,"mid":123,"uname":"测试"}}""")
                else -> error("Unexpected request")
            }
        }
        val challenge = client.generate()
        assertEquals(scan, challenge.url)
        assertFalse(challenge.toString().contains(key))
        assertSame(QrLoginStatus.Waiting, client.poll(challenge))
        assertSame(QrLoginStatus.Scanned, client.poll(challenge))
        val confirmed = client.poll(challenge) as QrLoginStatus.Confirmed
        assertFalse(confirmed.toString().contains("t%2C1"))
        assertEquals("SESSDATA=t%2C1; bili_jct=csrf", confirmed.cookie)
        assertEquals(123L, client.verifyAccount(confirmed.cookie).mid)
        requests.dropLast(1).forEach {
            assertEquals("passport.bilibili.com", it.url.host)
            assertNull(it.header("Cookie"))
        }
        requests.subList(1, 4).forEach { assertEquals(key, it.url.queryParameter("qrcode_key")) }
        assertEquals("api.bilibili.com", requests.last().url.host)
        assertEquals(confirmed.cookie, requests.last().header("Cookie"))
    }

    @Test fun generatedQrMustPointToOfficialScannerWithExactlyTheIssuedKey() {
        val bad = listOf(scan.replace("https:", "http:"), scan.replace(".com/", ".com.other/"),
            scan.replace(".com/", ".com:444/"), scan.replace("account.", "name@account."),
            scan.replace("/h5/account-h5/auth/scan-web", "/redirect"), scan.replace(key, "wrong"),
            "$scan&qrcode_key=another")
        bad.forEach { url ->
            val client = client { req -> reply(req, generated(url)) }
            val e = assertThrows(QrLoginException::class.java) { runBlocking { client.generate() } }
            assertFalse(e.message!!.contains(key))
        }
    }

    @Test fun expiryAndBusinessErrorsCannotAuthenticateEvenWithCookieHeaders() = runBlocking {
        val bodies = listOf(poll(86038), poll(1234),
            """{"code":-412,"message":"SECRET","data":{"code":0}}""",
            """{"code":0,"data":{"code":0,"url":"https://evil.example/SECRET?SESSDATA=fake"}}""").iterator()
        val client = client { req ->
            if (req.url.encodedPath.endsWith("/generate")) reply(req, generated())
            else reply(req, bodies.next(), cookies = listOf("SESSDATA=SECRET; Domain=evil.example"))
        }
        val challenge = client.generate()
        assertSame(QrLoginStatus.Expired, client.poll(challenge))
        repeat(3) {
            val e = assertThrows(QrLoginException::class.java) { runBlocking { client.poll(challenge) } }
            assertFalse(e.message!!.contains("SECRET"))
        }
    }

    @Test fun errorsDoNotExposeUpstreamBodyAndNavRejectsAnonymousResponse() {
        val client = client { req -> reply(req, """{"code":0,"data":{"isLogin":false,"mid":123}}""") }
        assertThrows(QrLoginException::class.java) { runBlocking { client.verifyAccount("SESSDATA=test") } }
        listOf(302, 412, 500).forEach { code ->
            val failed = client { req -> reply(req, "SECRET", code) }
            val e = assertThrows(QrLoginException::class.java) { runBlocking { failed.generate() } }
            assertTrue(e.message!!.contains(code.toString()))
            assertFalse(e.message!!.contains("SECRET"))
        }
        val malformed = client { req -> reply(req, "{SECRET}") }
        assertFalse(assertThrows(QrLoginException::class.java) { runBlocking { malformed.generate() } }
            .message!!.contains("SECRET"))
    }
}
