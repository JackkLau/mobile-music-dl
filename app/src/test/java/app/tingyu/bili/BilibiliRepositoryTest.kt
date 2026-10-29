package app.tingyu.bili

import app.tingyu.data.bili.*
import app.tingyu.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BilibiliRepositoryTest {
    @Test fun importedAudioNeverMakesANetworkRequest() = runTest {
        var requests = 0
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
            requests++
            error("Local audio must never be resolved online")
        })
        try {
            repository.resolveAudio(Track("BV1xx411c7mD", 0, "本地音频", localOnly = true))
            fail("Expected local-source rejection")
        } catch (error: BiliException) { assertEquals("local", error.stage) }
        assertEquals(0, requests)
    }

    private val video = """{"code":0,"data":{"bvid":"BV1xx411c7mD","title":"Music","cid":42,"duration":60}}"""
    private val nav = """{"code":-101,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
    private val audio = """{"code":0,"data":{"timelength":60000,"dash":{"audio":[{"baseUrl":"https://a.bilivideo.com/audio.m4s","codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":192000}]}}}"""

    @Test fun defaultApiAndAudioRequestsUseTheVerifiedDesktopProfile() = runTest {
        val repository = BilibiliRepository({ "" }, BiliTransport { url, headers ->
            assertEquals(UserAgentProfile.DESKTOP.value, headers["User-Agent"])
            BiliResponse(200, when (url.encodedPath) {
                "/x/web-interface/view" -> video
                "/x/web-interface/nav" -> nav
                else -> { assertEquals("pc", url.queryParameter("platform")); audio }
            })
        })
        val stream = repository.resolveAudio(Track("BV1xx411c7mD", 42, "Music"))
        assertEquals(UserAgentProfile.DESKTOP.value, stream.headers["User-Agent"])
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=1", stream.headers["Referer"])
    }

    @Test fun operationRetainsItsUaSnapshotWhenSettingsChangeDuringResolution() = runTest {
        var profile = NetworkProfile(parseUa = UserAgentProfile.MOBILE, audioUa = UserAgentProfile.DESKTOP)
        val repository = BilibiliRepository({ "" }, BiliTransport { url, headers ->
            assertEquals(UserAgentProfile.MOBILE.value, headers["User-Agent"])
            profile = NetworkProfile(parseUa = UserAgentProfile.DESKTOP, audioUa = UserAgentProfile.MOBILE)
            BiliResponse(200, when (url.encodedPath) {
                "/x/web-interface/view" -> video
                "/x/web-interface/nav" -> nav
                else -> audio
            })
        }, profileProvider = { profile })
        val stream = repository.resolveAudio(Track("BV1xx411c7mD", 42, "Music"))
        assertEquals(UserAgentProfile.DESKTOP.value, stream.headers["User-Agent"])
        assertEquals(UserAgentProfile.MOBILE, repository.profileSnapshot().audioUa)
    }

    @Test fun disablingLogsImmediatelyGatesAnAlreadyCapturedProfile() {
        var current = NetworkProfile(loggingEnabled = true)
        val output = mutableListOf<String>()
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ -> error("No request expected") },
            profileProvider = { current }, logSink = output::add)
        val captured = repository.profileSnapshot()
        repository.log(captured, "test-123", mapOf("stage" to "cdn", "http" to "206"))
        current = NetworkProfile(loggingEnabled = false)
        repository.log(captured, "test-123", mapOf("stage" to "save", "event" to "completed"))
        assertEquals(1, output.size)
    }

    @Test fun rejectedAudioAddressProducesSafeSelectionDiagnosticsOnlyWhenEnabled() = runTest {
        listOf(false, true).forEach { enabled ->
            val output = mutableListOf<String>()
            val rejectedAudio = audio.replace("https://a.bilivideo.com/audio.m4s",
                "https://a.bilivideo.com:8443/audio.m4s?token=PRIVATE_URL")
            val repository = BilibiliRepository({ "SESSDATA=PRIVATE_COOKIE" }, BiliTransport { url, _ ->
                BiliResponse(200, when (url.encodedPath) {
                    "/x/web-interface/view" -> video
                    "/x/web-interface/nav" -> nav.replace("\"code\":-101", "\"code\":0").replace("\"isLogin\":false", "\"isLogin\":true")
                    else -> rejectedAudio
                })
            }, profileProvider = { NetworkProfile(loggingEnabled = enabled) }, logSink = output::add)
            try {
                repository.resolveAudio(Track("BV1xx411c7mD", 42, "Music"))
                fail("Expected unusable audio address")
            } catch (expected: BiliException) {
                assertTrue(expected.message!!.contains("可用的音频地址"))
            }
            if (enabled) {
                assertTrue(output.any { "stage=audio_select" in it && "reason=port" in it })
                assertTrue(output.any { "event=selection_failed" in it && "reason=no_usable_url" in it })
                assertTrue(output.filter { "stage=audio_select" in it }.all { "parse_ua=desktop" in it })
            } else assertTrue(output.isEmpty())
            assertFalse(output.joinToString().contains("PRIVATE"))
            assertFalse(output.joinToString().contains("https://"))
        }
    }

    @Test fun anonymousRequestsOmitAccountCookies() = runTest {
        val repository = BilibiliRepository({ "" }, BiliTransport { _, headers ->
            assertFalse(headers.containsKey("Cookie"))
            BiliResponse(200, video)
        })
        assertEquals(42L, repository.parse("BV1xx411c7mD").tracks.single().cid)
    }

    @Test fun loginCookieIsOnlyAddedToApiAfterSafeShortLinkRedirect() = runTest {
        var requests = 0
        val repository = BilibiliRepository({ "SESSDATA=private" }, BiliTransport { url, headers ->
            requests++
            if (url.host == "b23.tv") {
                assertFalse(headers.containsKey("Cookie"))
                BiliResponse(302, "", "https://www.bilibili.com/video/BV1xx411c7mD")
            } else {
                assertEquals("api.bilibili.com", url.host)
                assertEquals("SESSDATA=private", headers["Cookie"])
                BiliResponse(200, video)
            }
        })
        repository.parse("https://b23.tv/test")
        assertEquals(2, requests)
    }

    @Test fun externalRedirectIsNeverRequested() = runTest {
        var requests = 0
        val repository = BilibiliRepository({ "SESSDATA=private" }, BiliTransport { _, _ ->
            requests++
            BiliResponse(302, "", "https://evil.example/video/BV1xx411c7mD")
        })
        try { repository.parse("https://b23.tv/test"); fail("Accepted external redirect") } catch (_: BiliException) { }
        assertEquals(1, requests)
    }

    @Test fun changingModeDiscardsInFlightAccountResults() = runTest {
        var cookie = "SESSDATA=private"
        val repository = BilibiliRepository({ cookie }, BiliTransport { _, _ ->
            cookie = ""
            BiliResponse(200, video)
        })
        try { repository.parse("BV1xx411c7mD"); fail("Returned old account data") }
        catch (expected: BiliException) { assertTrue(expected.message!!.contains("切换")) }
    }

    @Test fun throttlingProducesActionableFailure() = runTest {
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ -> BiliResponse(412, "blocked") })
        try { repository.parse("BV1xx411c7mD"); fail("Accepted throttled request") }
        catch (expected: BiliException) { assertTrue(expected.message!!.contains("稍后")) }
    }

    @Test fun favoritesReceiveFreshPartDurationAndParameterFailureRefreshesOnce() = runTest {
        var navRequests = 0
        var playRequests = 0
        val repository = BilibiliRepository({ "" }, BiliTransport { url, _ ->
            when (url.encodedPath) {
                "/x/web-interface/view" -> BiliResponse(200, video)
                "/x/web-interface/nav" -> { navRequests++; BiliResponse(200, nav) }
                "/x/player/wbi/playurl" -> {
                    playRequests++
                    assertEquals("42", url.queryParameter("cid"))
                    BiliResponse(200, if (playRequests == 1) """{"code":-400}""" else audio)
                }
                else -> error("Unexpected request")
            }
        })
        val stream = repository.resolveAudio(Track("BV1xx411c7mD", 0, "收藏夹条目", durationMs = 0))
        assertEquals(60_000L, stream.expectedDurationMs)
        assertEquals(2, navRequests)
        assertEquals(2, playRequests)
    }

    @Test fun repeatedParameterFailureDoesNotLoop() = runTest {
        var playRequests = 0
        val repository = BilibiliRepository({ "" }, BiliTransport { url, _ ->
            when (url.encodedPath) {
                "/x/web-interface/view" -> BiliResponse(200, video)
                "/x/web-interface/nav" -> BiliResponse(200, nav)
                else -> { playRequests++; BiliResponse(200, """{"code":-400}""") }
            }
        })
        try { repository.resolveAudio(Track("BV1xx411c7mD", 42, "Music")); fail("Expected parameter failure") }
        catch (expected: BiliException) { assertEquals(-400, expected.code) }
        assertEquals(2, playRequests)
    }

    @Test fun permissionAndRateFailuresAreNeverRetried() = runTest {
        listOf(-403, -352, -101).forEach { failure ->
            var playRequests = 0
            var navRequests = 0
            val repository = BilibiliRepository({ "" }, BiliTransport { url, _ ->
                when (url.encodedPath) {
                    "/x/web-interface/view" -> BiliResponse(200, video)
                    "/x/web-interface/nav" -> { navRequests++; BiliResponse(200, nav) }
                    else -> { playRequests++; BiliResponse(200, """{"code":$failure}""") }
                }
            })
            try { repository.resolveAudio(Track("BV1xx411c7mD", 42, "Music")); fail("Expected API failure") }
            catch (expected: BiliException) { assertEquals(failure, expected.code) }
            assertEquals(1, playRequests)
            assertEquals(1, navRequests)
        }
    }
    @Test fun allEntrypointsUseIdenticalStreamParametersAndPartReferer() = runTest {
        val partVideo = """{"code":0,"data":{"bvid":"BV1sVtw6JEeq","title":"Music","pages":[{"cid":41,"page":1,"duration":60},{"cid":42,"page":2,"duration":60}]}}"""
        val requests = mutableListOf<String>()
        val referers = mutableListOf<String?>()
        val repository = BilibiliRepository({ "" }, BiliTransport { url, _ ->
            when {
                url.host == "b23.tv" -> BiliResponse(302, "", "https://m.bilibili.com/video/BV1sVtw6JEeq?p=2&vd_source=ignored")
                url.encodedPath.endsWith("view") -> BiliResponse(200, partVideo)
                url.encodedPath.endsWith("nav") -> BiliResponse(200, nav)
                else -> { requests += url.toString(); BiliResponse(200, audio) }
            }
        }, nowSeconds = { 1000L })
        listOf("https://www.bilibili.com/video/BV1sVtw6JEeq/?p=2&vd_source=first",
            "https://m.bilibili.com/video/BV1sVtw6JEeq?p=2&vd_source=second", "[手机客户端分享](https://b23.tv/ZhRmMnf)").forEach {
            referers += repository.resolveAudio(repository.parse(it).tracks.single()).headers["Referer"]
        }
        assertEquals(3, requests.size)
        assertEquals(1, requests.distinct().size)
        assertEquals(listOf("https://www.bilibili.com/video/BV1sVtw6JEeq?p=2"), referers.distinct())
        assertTrue(requests.all { "vd_source" !in it })
    }

    @Test fun shortLinkHttpFailuresKeepStageAndExactStatusWithoutFollowup() = runTest {
        mapOf(401 to "授权失败", 403 to "拒绝访问", 404 to "不存在", 410 to "过期", 412 to "限制请求", 429 to "限制请求").forEach { (status, label) ->
            var calls = 0
            val lines = mutableListOf<String>()
            val repository = BilibiliRepository({ "SESSDATA=PRIVATE" }, BiliTransport { _, headers ->
                calls++
                assertFalse(headers.containsKey("Cookie"))
                BiliResponse(status, "PRIVATE server response")
            }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
            try { repository.parse("https://b23.tv/ZhRmMnf?secret=PRIVATE"); fail("Expected HTTP $status") }
            catch (error: BiliException) {
                assertEquals("short_link", error.stage)
                assertEquals(status, error.httpStatus)
                assertEquals(status, error.code)
                assertTrue(error.message!!.contains(label))
                assertFalse(error.message!!.contains("PRIVATE"))
            }
            assertEquals(1, calls)
            assertTrue(lines.any { "stage=short_link" in it && "http=$status" in it })
            assertFalse(lines.joinToString().contains("PRIVATE"))
        }
    }

    @Test fun shortLinkBusinessErrorsPreserveCodesWithoutRawServerText() = runTest {
        listOf(-101, -403, -352).forEach { code ->
            val lines = mutableListOf<String>()
            val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
                BiliResponse(200, """{"code":$code,"message":"PRIVATE"}""")
            }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
            try { repository.parse("https://b23.tv/test"); fail("Expected business failure") }
            catch (error: BiliException) {
                assertEquals("short_link", error.stage)
                assertEquals(200, error.httpStatus)
                assertEquals(code, error.code)
            }
            assertTrue(lines.any { "code=$code" in it && "stage=short_link" in it })
            assertFalse(lines.joinToString().contains("PRIVATE"))
        }
    }

    @Test fun shortLinkNetworkFailureRetainsCauseAndSafeStage() = runTest {
        val lines = mutableListOf<String>()
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
            throw java.net.UnknownHostException("PRIVATE host")
        }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
        try { repository.parse("https://b23.tv/test"); fail("Expected DNS failure") }
        catch (error: BiliException) {
            assertEquals("short_link", error.stage)
            assertTrue(error.cause is java.net.UnknownHostException)
            assertNull(error.httpStatus)
            assertFalse(error.message!!.contains("PRIVATE"))
        }
        assertTrue(lines.any { "stage=short_link" in it && "failure=dns" in it })
        assertFalse(lines.joinToString().contains("PRIVATE"))
    }

    @Test fun missingAndUnsupportedRedirectsPreserveHttpStatus() = runTest {
        listOf(BiliResponse(302, ""), BiliResponse(304, "", "https://www.bilibili.com/video/BV1xx411c7mD"),
            BiliResponse(302, "", "http://www.bilibili.com/video/BV1xx411c7mD")).forEach { response ->
            var calls = 0
            val repository = BilibiliRepository({ "" }, BiliTransport { _, _ -> calls++; response })
            try { repository.parse("https://b23.tv/test"); fail("Expected redirect failure") }
            catch (error: BiliException) {
                assertEquals("short_link", error.stage)
                assertEquals(response.status, error.httpStatus)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun redirectLoopIsBoundedAndCancellationIsNotConvertedIntoFailure() = runTest {
        var calls = 0
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
            calls++; BiliResponse(302, "", "/next$calls")
        })
        try { repository.parse("https://b23.tv/test"); fail("Expected redirect limit") }
        catch (error: BiliException) { assertEquals("short_link", error.stage) }
        assertEquals(5, calls)
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        val cancelled = BilibiliRepository({ "" }, BiliTransport { _, _ -> throw cancellation })
        try { cancelled.parse("https://b23.tv/test"); fail("Expected cancellation") }
        catch (error: kotlinx.coroutines.CancellationException) { assertSame(cancellation, error) }
    }

    @Test fun apiBusinessAndErrorPageFailuresRetainTheirStage() = runTest {
        val lines = mutableListOf<String>()
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
            BiliResponse(200, """{"code":-101,"message":"PRIVATE"}""")
        }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
        try { repository.parse("BV1xx411c7mD"); fail("Expected authorization failure") }
        catch (error: BiliException) {
            assertEquals("api", error.stage)
            assertEquals(200, error.httpStatus)
            assertEquals(-101, error.code)
        }
        assertTrue(lines.any { "stage=api" in it && "code=-101" in it })
        assertFalse(lines.joinToString().contains("PRIVATE"))
        val html = BilibiliRepository({ "" }, BiliTransport { _, _ -> BiliResponse(200, "<html>PRIVATE</html>") })
        try { html.parse("BV1xx411c7mD"); fail("Accepted error page") }
        catch (error: BiliException) {
            assertEquals("api", error.stage)
            assertEquals(200, error.httpStatus)
            assertFalse(error.message!!.contains("PRIVATE"))
        }
    }

    @Test fun explicitParseProfileAndRequestIdCoverShortLinkAndApi() = runTest {
        val lines = mutableListOf<String>()
        val profile = NetworkProfile(parseUa = UserAgentProfile.MOBILE, loggingEnabled = true)
        val repository = BilibiliRepository({ "" }, BiliTransport { url, headers ->
            assertEquals(UserAgentProfile.MOBILE.value, headers["User-Agent"])
            if (url.host == "b23.tv") BiliResponse(302, "", "https://m.bilibili.com/video/BV1xx411c7mD") else BiliResponse(200, video)
        }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
        repository.parse("https://b23.tv/test", profile, "shared-request")
        assertTrue(lines.all { "request=shared-request" in it })
        assertTrue(lines.any { "stage=short_link" in it && "ua=mobile" in it })
        assertTrue(lines.any { "stage=api" in it && "ua=mobile" in it })
    }

    @Test fun transportFailureRetainsConnectionFamilyProxyAndNetworkStage() = runTest {
        val lines = mutableListOf<String>()
        val metadata = ConnectionMetadata().apply { family = "ipv6"; proxy = "http"; stage = "tls" }
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ ->
            throw BiliException("无法连接 B 站，请检查网络后重试", connection = metadata).apply {
                initCause(javax.net.ssl.SSLException("PRIVATE certificate/address"))
            }
        }, profileProvider = { NetworkProfile(loggingEnabled = true) }, logSink = lines::add)
        try { repository.parse("BV1xx411c7mD"); fail("Expected TLS failure") }
        catch (error: BiliException) { assertSame(metadata, error.connection); assertEquals("api", error.stage) }
        assertTrue(lines.any { "family=ipv6" in it && "proxy=http" in it && "network_stage=tls" in it && "failure=tls" in it })
        assertFalse(lines.joinToString().contains("PRIVATE"))
    }

    @Test fun businessFailurePreservesActualSuccessfulHttpStatus() = runTest {
        val repository = BilibiliRepository({ "" }, BiliTransport { _, _ -> BiliResponse(202, """{"code":-101}""") })
        try { repository.parse("BV1xx411c7mD"); fail("Expected authorization failure") }
        catch (error: BiliException) { assertEquals(202, error.httpStatus); assertEquals(-101, error.code) }
    }

}
