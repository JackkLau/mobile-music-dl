package app.tingyu.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class GitHubReleaseTest {
    private val bytes = "APK fixture".toByteArray()
    private val release = GitHubRelease("0.10.2", bytes.size.toLong(), bytes.toByteString().sha256().hex())
    private val apkAsset = """{"name":"${release.fileName}","size":${bytes.size},"digest":"sha256:${release.sha256}","browser_download_url":"${release.url}"}"""
    private fun metadata(vararg apks: String) = """
        {"tag_name":"v0.10.2","draft":false,"prerelease":false,"assets":[
        {"name":"weile-0.10.2-release.aab"},${apks.joinToString(",")}]}
    """.trimIndent()
    private val metadata = metadata(apkAsset)

    @Test fun findsOnlyTheStableOfficialApkAndComparesVersionPartsAsNumbers() {
        val actual = GitHubRelease.parse(metadata)
        assertEquals(release, actual)
        assertTrue(actual.compareToInstalled("0.9.12") > 0)
        assertTrue(actual.compareToInstalled("1.0.0") < 0)
        assertEquals(0, actual.compareToInstalled("0.10.2"))
    }

    @Test fun prefersTheBrandedApkAndStillReadsOlderReleases() {
        val oldAsset = apkAsset.replace("weile-", "tingyu-")
        val legacy = GitHubRelease.parse(metadata(oldAsset))
        assertTrue(legacy.legacy)
        assertEquals(release.fileName, legacy.fileName)
        assertTrue(legacy.url.endsWith("/tingyu-0.10.2-release.apk"))

        val both = metadata(oldAsset, apkAsset)
        assertEquals(release, GitHubRelease.parse(both))
        assertThrows(UpdateError::class.java) {
            GitHubRelease.parse(both.replace(release.url, "https://other.example/wrong.apk"))
        }
    }

    @Test fun rejectsWrongRepositoryOrUnverifiableRelease() {
        listOf(
            metadata.replace("/JackkLau/", "/other/"),
            metadata.replace("/download/v0.10.2/", "/download/v0.10.1/"),
            metadata.replace("sha256:", "md5:"),
            metadata.replace("\"draft\":false", "\"draft\":true"),
            metadata.replace("\"prerelease\":false", "\"prerelease\":true"),
            metadata.replace("\"tag_name\":\"v0.10.2\"", "\"tag_name\":\"v0.10.2-rc1\""),
            metadata.replace("\"size\":${bytes.size}", "\"size\":999999999"),
        ).forEach { assertThrows(UpdateError::class.java) { GitHubRelease.parse(it) } }
    }

    @Test fun acceptsOnlyGitHubAssetStorageForRedirects() {
        assertTrue(allowedAssetRedirect("https://release-assets.githubusercontent.com/asset?sig=test".toHttpUrl()))
        listOf("http://release-assets.githubusercontent.com/x", "https://release-assets.githubusercontent.com.evil.example/x",
            "https://x@objects.githubusercontent.com/x", "https://objects.githubusercontent.com:444/x").forEach {
            assertFalse(allowedAssetRedirect(it.toHttpUrl()))
        }
    }

    @Test fun rejectsPartialOversizeAndSameLengthCorruptedDownloads() {
        listOf(bytes.copyOf(bytes.size - 1), bytes + 0, bytes.reversedArray()).forEach {
            assertThrows(UpdateError::class.java) { copyVerifiedApk(it.inputStream(), ByteArrayOutputStream(), release) {} }
        }
        val output = ByteArrayOutputStream()
        var received = 0L
        copyVerifiedApk(bytes.inputStream(), output, release) { received = it }
        assertEquals(release.size, received)
        assertArrayEquals(bytes, output.toByteArray())
    }
}
