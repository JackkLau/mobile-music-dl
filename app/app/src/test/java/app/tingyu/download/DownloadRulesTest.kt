package app.tingyu.download

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    @Test fun `authorization expired and throttled failures retain their actual status`() {
        listOf(401, 403, 404, 410, 412, 429).forEach { code ->
            assertTrue(DownloadRules.httpMessage(code).contains("HTTP $code"))
        }
        assertNotEquals(DownloadRules.httpMessage(403), DownloadRules.httpMessage(410))
        assertTrue(DownloadRules.httpMessage(403).contains("拒绝"))
        assertTrue(DownloadRules.httpMessage(410).contains("过期"))
    }
    @Test fun `resumes only a matching strong entity at the exact offset`() {
        assertTrue(DownloadRules.canAppend(206, 512, 1024, "\"v1\"", "\"v1\"", "bytes 512-1023/1024"))
        assertFalse(DownloadRules.canAppend(206, 512, 1024, "\"v1\"", "\"v2\"", "bytes 512-1023/1024"))
        assertFalse(DownloadRules.canAppend(206, 512, 1024, "W/\"v1\"", "W/\"v1\"", "bytes 512-1023/1024"))
        assertFalse(DownloadRules.canAppend(200, 512, 1024, "\"v1\"", "\"v1\"", null))
        assertFalse(DownloadRules.canAppend(206, 512, 1024, "\"v1\"", "\"v1\"", "bytes 0-1023/1024"))
        assertFalse(DownloadRules.canAppend(206, 512, 2048, "\"v1\"", "\"v1\"", "bytes 512-1023/1024"))
    }

    @Test fun `rejects invalid and overflowing range responses`() {
        listOf("bytes 5-3/10", "bytes 0-10/10", "bytes 0-5/*", "items 0-2/5", "bytes 0-999999999999999999999/4").forEach {
            assertNull(it, DownloadRules.parseContentRange(it))
        }
    }

    @Test fun `media requests cannot target local addresses or lookalike domains`() {
        assertTrue(DownloadRules.permittedMediaUrl("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/a.m4s?x=1"))
        listOf("http://cdn.bilivideo.com/a", "https://127.0.0.1/a", "https://bilivideo.com.evil.test/a", "https://evilbilivideo.com/a", "https://user:pass@cdn.bilivideo.com/a", "https://cdn.bilivideo.com:8443/a").forEach {
            assertFalse(it, DownloadRules.permittedMediaUrl(it))
        }
    }

    @Test fun `known mcdn TLS media port is usable without changing its signature`() {
        assertTrue(DownloadRules.permittedMediaUrl("https://mcdn.bilivideo.cn:4483/audio.m4s?token=a%2fb%2Bc"))
        assertTrue(DownloadRules.permittedMediaUrl("https://xy.mcdn.bilivideo.cn:4483/audio.m4s"))
    }

    @Test fun `the extra CDN port is not enabled for other hosts or cleartext`() {
        listOf(
            "https://a.bilivideo.com:4483/audio.m4s",
            "https://bilivideo.cn:4483/audio.m4s",
            "https://evilmcdn.bilivideo.cn:4483/audio.m4s",
            "https://mcdn.bilivideo.cn:8443/audio.m4s",
            "http://mcdn.bilivideo.cn:4483/audio.m4s",
        ).forEach { assertFalse(it, DownloadRules.permittedMediaUrl(it)) }
    }

    @Test fun `names cannot create paths and remain distinguishable across parts`() {
        assertEquals("a_b_c_-BV123-P2.m4a", DownloadRules.fileName("a/b\\c?", "BV123", 2))
        assertEquals("音频-BV123-P1.m4a", DownloadRules.fileName("...", "BV123", 1))
        assertNotEquals(DownloadRules.fileName("同名", "BV123", 1), DownloadRules.fileName("同名", "BV123", 2))
    }

    @Test fun `truncation is never reported complete`() {
        assertFalse(DownloadRules.complete(1000, 999))
        assertFalse(DownloadRules.complete(1000, 1001))
        assertFalse(DownloadRules.complete(-1, 0))
        assertTrue(DownloadRules.complete(1000, 1000))
    }
}
