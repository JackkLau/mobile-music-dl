package app.tingyu.bili

import app.tingyu.data.bili.BiliException
import app.tingyu.data.bili.BiliResponseParser
import app.tingyu.data.bili.WbiSigner
import org.junit.Assert.*
import org.junit.Test

class BiliResponseParserTest {
    @Test fun partSelectionPreservesDurationAndIdentity() {
        val data = BiliResponseParser.root("""{
          "bvid":"BV1xx411c7mD","title":"演出","pic":"http://i0.hdslb.com/a.jpg","owner":{"name":"演奏者"},
          "pages":[{"cid":11,"page":1,"part":"上半场","duration":90},{"cid":12,"page":2,"part":"下半场","duration":180}]
        }""")
        val result = BiliResponseParser.video(data, 2)
        assertEquals(1, result.tracks.size)
        assertEquals(12L, result.tracks.single().cid)
        assertEquals(180_000L, result.tracks.single().durationMs)
        assertEquals("https://i0.hdslb.com/a.jpg", result.tracks.single().coverUrl)
    }

    @Test fun favoritesKeepPaginationEvenWhenPageContainsNoSupportedVideos() {
        val data = BiliResponseParser.root("""{"info":{"title":"歌单"},"medias":[{"type":12,"title":"音频"}],"has_more":true}""")
        val result = BiliResponseParser.favorites(data, 55, 2)
        assertTrue(result.tracks.isEmpty())
        assertEquals(3, result.nextPage)
        assertEquals(55L, result.favoriteId)
    }

    @Test fun choosesCompatibleAudioAndNeverPassesCookiesToCdn() {
        val data = BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[
          {"baseUrl":"https://a.bilivideo.com/low.m4s","codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":64000},
          {"baseUrl":"https://a.bilivideo.com/high.m4s","codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":192000,"backupUrl":["https://evil.example/stolen","https://b.bilivideo.com/high.m4s"]},
          {"baseUrl":"https://a.bilivideo.com/flac.m4s","codecs":"fLaC","mimeType":"audio/mp4","bandwidth":900000}
        ]}}""")
        val stream = BiliResponseParser.audio(data, 180_000, "https://www.bilibili.com/video/BV1xx411c7mD")
        assertEquals("https://a.bilivideo.com/high.m4s", stream.url)
        assertEquals(180_000L, stream.expectedDurationMs)
        assertEquals(listOf("https://b.bilivideo.com/high.m4s"), stream.backupUrls)
        assertFalse(stream.headers.keys.any { it.equals("cookie", true) })
    }

    @Test fun usesSafeBackupWhenPrimaryUrlIsUnsupported() {
        val stream = parseAudio("""{
          "codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":192000,
          "baseUrl":"https://untrusted.example/audio.m4s",
          "backupUrl":["https://a.bilivideo.com/audio.m4s","https://a.bilivideo.com/audio.m4s"],
          "backup_url":["https://b.bilivideo.com/audio.m4s"]
        }""")
        assertEquals("https://a.bilivideo.com/audio.m4s", stream.url)
        assertEquals(listOf("https://b.bilivideo.com/audio.m4s"), stream.backupUrls)
        assertEquals(192000L, stream.bitrate)
    }

    @Test fun missingMimeDoesNotRejectExplicitAacCodec() {
        listOf("mp4a.40.2", "MP4A.40.5", "mp4a.40.29", "mp4a.40.39", "mp4a.40.42").forEach { codec ->
            assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
              "codecs":"$codec","base_url":"https://a.bilivideo.com/audio.m4s"
            }""").url)
        }
    }

    @Test fun knownAacIdsCanRecoverMissingCodecMetadata() {
        listOf(30216, 30232, 30280).forEach { id ->
            assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
              "id":$id,"mime_type":"audio/mp4","baseUrl":"https://a.bilivideo.com/audio.m4s"
            }""").url)
        }
    }

    @Test fun explicitNonAacMetadataIsNotPromotedToAac() {
        listOf(
            "\"id\":30251,\"mimeType\":\"audio/mp4\"",
            "\"id\":30280,\"codecs\":\"fLaC\",\"mimeType\":\"audio/mp4\"",
            "\"id\":30280,\"codecs\":\"ec-3\",\"mimeType\":\"audio/mp4\"",
            "\"id\":30280,\"codecs\":\"opus\",\"mimeType\":\"audio/mp4\"",
            "\"id\":30280,\"codecs\":\"mp4a.6b\",\"mimeType\":\"audio/mp4\"",
            "\"codecs\":\"mp4a.40.2\",\"mimeType\":\"audio/webm\"",
        ).forEach { metadata ->
            try {
                parseAudio("""{$metadata,"baseUrl":"https://a.bilivideo.com/audio.m4s"}""")
                fail("Accepted incompatible metadata: $metadata")
            } catch (expected: BiliException) { assertTrue(expected.message!!.contains("AAC")) }
        }
    }

    @Test fun explicitAacDoesNotRequireAnAudioOnlyMimeLabel() {
        listOf("application/octet-stream", "application/mp4", "video/mp4", "audio/x-m4a").forEach { mime ->
            assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
              "codecs":"mp4a.40.2","mimeType":"$mime","baseUrl":"https://a.bilivideo.com/audio.m4s"
            }""").url)
        }
    }

    @Test fun aacCodecInsideMimeParametersIsRecognizedWithoutRepresentationId() {
        assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
          "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","baseUrl":"https://a.bilivideo.com/audio.m4s"
        }""").url)
    }

    @Test fun mpeg2AacAndPaddedAacLcCodecAreRecognized() {
        listOf("mp4a.66", "mp4a.67", "mp4a.68", "mp4a.40.02").forEach { codec ->
            assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
              "codecs":"$codec","mimeType":"audio/mp4","baseUrl":"https://a.bilivideo.com/audio.m4s"
            }""").url)
        }
    }

    @Test fun audioMp4WithMissingCodecCanReachContentInspectionWithoutAKnownId() {
        assertEquals("https://a.bilivideo.com/audio.m4s", parseAudio("""{
          "id":99999,"mimeType":"audio/mp4","baseUrl":"https://a.bilivideo.com/audio.m4s"
        }""").url)
    }

    @Test fun confirmedAacHasPriorityOverHigherBitrateUnidentifiedMp4Audio() {
        val data = BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[
          {"id":99999,"mimeType":"audio/mp4","bandwidth":900000,"baseUrl":"https://a.bilivideo.com/unknown.m4s"},
          {"codecs":"mp4a.40.2","bandwidth":64000,"baseUrl":"https://a.bilivideo.com/aac.m4s"}
        ]}}""")
        assertEquals("https://a.bilivideo.com/aac.m4s", BiliResponseParser.audio(data, 180000,
            "https://www.bilibili.com/video/BV1xx411c7mD").url)
    }

    @Test fun unusableMediaAddressesAreNotReportedAsMissingAac() {
        try {
            parseAudio("""{"codecs":"mp4a.40.2","baseUrl":"https://untrusted.example/audio.m4s"}""")
            fail("Expected unusable address error")
        } catch (expected: BiliException) {
            assertTrue(expected.message!!.contains("地址"))
            assertFalse(expected.message!!.contains("未找到兼容"))
        }
    }

    @Test fun emptyAudioListHasItsOwnError() {
        try {
            BiliResponseParser.audio(BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[]}}"""),
                180000, "https://www.bilibili.com/video/BV1xx411c7mD")
            fail("Expected no audio error")
        } catch (expected: BiliException) {
            assertTrue(expected.message!!.contains("未返回独立音轨"))
        }
    }

    @Test fun conflictingCodecParametersStillRejectNonAac() {
        listOf("", "\"codecs\":\"mp4a.40.2\",").forEach { codec ->
            try {
                parseAudio("""{$codec"id":30280,"mimeType":"audio/mp4; codecs=\"fLaC\"",
                    "baseUrl":"https://a.bilivideo.com/audio.m4s"}""")
                fail("A known ID or AAC field must not hide conflicting FLAC metadata")
            } catch (_: BiliException) { }
        }
    }

    @Test fun uncertainMp4AudioIsMarkedForInspectionRatherThanClaimedAsAac() {
        val events = mutableListOf<Map<String, String>>()
        BiliResponseParser.audio(BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[
          {"mimeType":"audio/mp4","baseUrl":"https://a.bilivideo.com/audio.m4s"}
        ]}}"""), 180000, "https://www.bilibili.com/video/BV1xx411c7mD", events::add)
        assertEquals("inspect", events.last()["format"])
        assertEquals("selected", events.last()["event"])
    }

    @Test fun selectionDiagnosticsExplainRejectedAddressesWithoutLoggingServerData() {
        val events = mutableListOf<Map<String, String>>()
        try {
            BiliResponseParser.audio(BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[
              {"codecs":"mp4a.40.2","baseUrl":"https://a.bilivideo.com:8443/audio.m4s?token=PRIVATE"},
              {"codecs":"SESSDATA=PRIVATE","mimeType":"cookie=PRIVATE","baseUrl":"https://a.bilivideo.com/private"}
            ]}}"""), 180000, "https://www.bilibili.com/video/BV1xx411c7mD", events::add)
            fail("Expected no usable media URL")
        } catch (_: BiliException) { }
        assertEquals("port", events.first()["reason"])
        assertEquals("no_usable_url", events.last()["reason"])
        assertEquals("2", events.last()["audio_total"])
        assertFalse(events.toString().contains("PRIVATE"))
        assertFalse(events.toString().contains("https://"))
    }

    @Test fun mcdnPortRemainsUsableFromSelectionThroughRequestValidation() {
        val url = "https://xy1.mcdn.bilivideo.cn:4483/audio.m4s?sign=a%2Fb+z&x=1&x=2"
        val stream = parseAudio("""{"codecs":"mp4a.40.2","mimeType":"audio/mp4","baseUrl":"$url"}""")
        assertEquals(url, stream.url)
        assertTrue(app.tingyu.download.DownloadRules.permittedMediaUrl(stream.url))
        assertEquals(url, app.tingyu.data.bili.MediaRequestPolicy.request(stream.url, stream,
            app.tingyu.data.bili.UserAgentProfile.DESKTOP).url.toString())
    }

    @Test fun missingMetadataDoesNotBypassProtectionFlags() {
        listOf("\"is_drm\":true", "\"drm_tech_type\":1", "\"ContentProtection\":{}", "\"contentProtection\":{}").forEach { protection ->
            try {
                parseAudio("""{"id":30280,"mimeType":"audio/mp4",$protection,"baseUrl":"https://a.bilivideo.com/audio.m4s"}""")
                fail("Accepted protected representation")
            } catch (_: BiliException) { }
        }
    }

    @Test fun fallsBackToNextAacRepresentationWhenAllUrlsAreUnsafe() {
        val data = BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[
          {"id":30280,"bandwidth":192000,"baseUrl":"https://evil.example/audio.m4s"},
          {"id":30232,"bandwidth":132000,"baseUrl":"https://a.bilivideo.com/audio.m4s"}
        ]}}""")
        val stream = BiliResponseParser.audio(data, 180000, "https://www.bilibili.com/video/BV1xx411c7mD?p=2")
        assertEquals(132000L, stream.bitrate)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=2", stream.headers["Referer"])
    }

    @Test fun signedHttpsUrlIsPreservedByteForByte() {
        val signed = "https://a.bilivideo.com/audio.m4s?token=a%2fb%2Bc+z&deadline=42&x=1&x=2"
        assertEquals(signed, BiliResponseParser.safeMediaUrl(signed))
        assertEquals(signed, parseAudio("""{"codecs":"mp4a.40.2","baseUrl":"$signed"}""").url)
    }

    @Test fun mediaUrlValidationRejectsCredentialsPortsAndLookalikeHosts() {
        listOf(
            "https://a.bilivideo.com.evil.example/audio.m4s",
            "https://user:password@a.bilivideo.com/audio.m4s",
            "https://a.bilivideo.com:8443/audio.m4s",
            "https://a.bilivideo.com/audio.m4s\n",
        ).forEach { assertNull(BiliResponseParser.safeMediaUrl(it)) }
    }

    private fun parseAudio(representation: String) = BiliResponseParser.audio(
        BiliResponseParser.root("""{"timelength":180000,"dash":{"audio":[$representation]}}"""),
        180000, "https://www.bilibili.com/video/BV1xx411c7mD",
    )

    @Test fun previewAndTruncatedAudioAreNotReportedAsSuccess() {
        listOf(
            """{"is_preview":1,"dash":{"audio":[]}}""",
            """{"timelength":30000,"dash":{"audio":[]}}""",
            """{"accept_description":["试看"],"dash":{"audio":[]}}""",
        ).forEach {
            try { BiliResponseParser.audio(BiliResponseParser.root(it), 180_000, "https://www.bilibili.com/"); fail("Accepted preview") }
            catch (expected: BiliException) { assertTrue(expected.message!!.contains("完整")) }
        }
    }

    @Test fun apiFailuresNeverUseUntrustedServerMessage() {
        val root = BiliResponseParser.root("""{"code":-101,"message":"secret-cookie-value"}""")
        try { BiliResponseParser.data(root); fail("Expected login error") }
        catch (expected: BiliException) { assertFalse(expected.message!!.contains("secret-cookie-value")); assertEquals(-101, expected.code) }
    }

    @Test fun signingMatchesFixedTimestampFixture() {
        val key = WbiSigner.mixinKey(
            "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
            "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png",
        )
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        val signed = WbiSigner.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169L)
        // Digest independently calculated from the canonical query using Python hashlib.
        assertEquals("6149fdadf571698ca7e6a567265cd0ee", signed["w_rid"])
    }
}
