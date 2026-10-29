package app.tingyu.bili

import app.tingyu.data.bili.*
import app.tingyu.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BilibiliRepositoryTest {
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
}
