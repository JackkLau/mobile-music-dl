package app.tingyu.bili

import app.tingyu.data.bili.MediaUrlPolicy
import app.tingyu.download.DownloadRules
import org.junit.Assert.*
import org.junit.Test

class MediaUrlPolicyTest {
    @Test fun signedHttpsAddressesAreKeptExactlyIncludingExplicitPorts() {
        listOf(
            "https://a.bilivideo.com:443/audio.m4s?token=a%2fb%2Bc+z&x=1&x=2",
            "https://mcdn.bilivideo.cn:4483/audio.m4s?token=a%2fb%2Bc+z&x=1&x=2",
            "https://node.mcdn.bilivideo.cn:4483/audio.m4s?deadline=42&sign=private%2Fvalue",
        ).forEach { url ->
            assertEquals(url, MediaUrlPolicy.normalize(url))
            assertTrue(MediaUrlPolicy.permitted(url))
            assertTrue(DownloadRules.permittedMediaUrl(url))
            assertNull(MediaUrlPolicy.rejectionReason(url))
        }
    }

    @Test fun allPreviouslyTrustedCdnRootsStillWorkAtDefaultHttpsPort() {
        listOf("bilivideo.com", "bilivideo.cn", "bilivideo.net", "akamaized.net", "hdslb.com").forEach { host ->
            listOf(host, "node.$host").forEach {
                val url = "https://$it/audio.m4s"
                assertEquals(url, MediaUrlPolicy.normalize(url))
                assertTrue(MediaUrlPolicy.permitted(url))
            }
        }
    }

    @Test fun defaultHttpMayBeUpgradedButIsNeverDirectlyRequested() {
        listOf("http://a.bilivideo.com/audio.m4s?x=1", "http://a.bilivideo.com:80/audio.m4s?x=1").forEach { url ->
            assertEquals("https://a.bilivideo.com/audio.m4s?x=1", MediaUrlPolicy.normalize(url))
            assertFalse(MediaUrlPolicy.permitted(url))
            assertFalse(DownloadRules.permittedMediaUrl(url))
        }
    }

    @Test fun extraTlsPortIsStrictlyLimitedToMcdnAndItsSubdomains() {
        listOf(
            "https://a.bilivideo.com:4483/audio.m4s",
            "https://bilivideo.cn:4483/audio.m4s",
            "https://evilmcdn.bilivideo.cn:4483/audio.m4s",
            "https://mcdn.bilivideo.cn:8443/audio.m4s",
            "https://mcdn.bilivideo.cn:80/audio.m4s",
            "http://mcdn.bilivideo.cn:4483/audio.m4s",
        ).forEach { assertRejected(it, "port") }
    }

    @Test fun credentialsIncludingEmptyUserInfoAreRejected() {
        listOf("https://user:pass@a.bilivideo.com/audio", "https://@a.bilivideo.com/audio").forEach {
            assertRejected(it, "credentials")
        }
    }

    @Test fun fragmentsAndWhitespaceAreRejectedRatherThanSilentlyRewritten() {
        listOf("https://a.bilivideo.com/audio#section", "https://a.bilivideo.com/audio#").forEach {
            assertRejected(it, "fragment")
        }
        listOf(
            " https://a.bilivideo.com/audio", "https://a.bilivideo.com/audio\n",
            "https://a.bilivideo.com/a udio", "https://a.bilivideo.com/a\tudio",
            "https://a.bilivideo.com\\audio",
        ).forEach { assertRejected(it, "invalid_url") }
    }

    @Test fun untrustedHostsAndNonHttpSchemesDoNotPassEitherBoundary() {
        listOf("https://127.0.0.1/audio", "https://a.bilivideo.com.evil.example/audio", "https://www.bilibili.com/audio").forEach {
            assertRejected(it, "host")
        }
        listOf("ftp://a.bilivideo.com/audio", "file:///audio", "javascript:alert(1)").forEach {
            assertRejected(it, "scheme")
        }
        assertRejected("not-a-url", "invalid_url")
    }

    private fun assertRejected(url: String, reason: String) {
        assertEquals(reason, MediaUrlPolicy.rejectionReason(url))
        assertNull(MediaUrlPolicy.normalize(url))
        assertFalse(MediaUrlPolicy.permitted(url))
        assertFalse(DownloadRules.permittedMediaUrl(url))
    }
}
