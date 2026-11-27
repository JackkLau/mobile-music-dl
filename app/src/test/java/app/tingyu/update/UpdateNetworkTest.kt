package app.tingyu.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UpdateNetworkTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val repository = "music-team/mobile-music-dl"
    private val payload = "signed APK fixture"
    private val release = GitHubRelease("1.0.0", payload.length.toLong(), payload.encodeUtf8().sha256().hex())
    private val client = releaseClient().newBuilder()
        .readTimeout(2, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val url = chain.request().url
            chain.proceed(chain.request().newBuilder().url(server.url("/${url.host}${url.encodedPath}")).build())
        }.build()
    private val updates = UpdateNetwork(repository, client)

    @Before fun startServer() = server.start()
    @After fun stopServer() = server.shutdown()

    private fun respond(text: String) = server.enqueue(MockResponse().setBody(text))
    private fun redirect(url: String) = server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url))
    private fun paths() = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
    private val mirrorApi = "/gitee.com/api/v5/repos/$repository/releases/latest"
    private val githubApi = "/api.github.com/repos/JackkLau/mobile-music-dl/releases/latest"
    private val mirrorApk = "/gitee.com/$repository/releases/download/v1.0.0/${release.fileName}"
    private val githubApk = "/github.com/JackkLau/mobile-music-dl/releases/download/v1.0.0/${release.fileName}"

    private fun giteeMetadata(
        manifest: String = """{"package":"app.tingyu","versionName":"1.0.0","certificateSha256":"$RELEASE_SIGNER","assets":[
            {"file":"${release.fileName}","bytes":${release.size},"sha256":"${release.sha256}"}]}""",
        attachment: String = """{"name":"${release.fileName}","browser_download_url":"${release.giteeUrl(repository)}"}""",
    ) = """{"tag_name":"v1.0.0","prerelease":false,"assets":[$attachment],"body":${JsonPrimitive(manifest)}}"""

    private fun githubMetadata(apk: GitHubRelease = release) = """{"tag_name":"v${apk.version}","draft":false,"prerelease":false,
        "assets":[{"name":"${apk.fileName}","size":${apk.size},"digest":"sha256:${apk.sha256}","browser_download_url":"${apk.url}"}]}"""

    @Test fun availableMirrorHandlesCheckAndDownloadWithoutContactingGitHub() = runBlocking {
        respond(giteeMetadata())
        redirect("https://gitee.com/$repository/attach_files/123/download/${release.fileName}")
        redirect("https://foruda.gitee.com/attach_file/456/${release.fileName}?signature=test")
        respond(payload)

        val actual = updates.latest("0.9.9")
        val tmp = folder.newFile()
        updates.download(actual, tmp) {}

        assertEquals(release, actual)
        assertEquals(payload, tmp.readText())
        assertEquals(listOf(mirrorApi, mirrorApk, "/gitee.com/$repository/attach_files/123/download/${release.fileName}",
            "/foruda.gitee.com/attach_file/456/${release.fileName}"), paths())
    }

    @Test fun incompleteOrUntrustedReleaseRecordsCannotSelectAnApk() {
        val metadata = giteeMetadata()
        for (bad in listOf(
            metadata.replace("app.tingyu", "other"),
            metadata.replace(RELEASE_SIGNER, "0".repeat(64)),
            metadata.replace("v1.0.0", "v1.0.1"),
            metadata.replace("false", "true"),
            metadata.replace(release.sha256, "0"),
            metadata.replace("https://gitee.com/", "https://example.org/"),
            giteeMetadata(attachment = ""),
            giteeMetadata(manifest = "<html>请登录</html>"),
        )) assertThrows(UpdateError::class.java) { GitHubRelease.parseGitee(bad, repository) }
    }

    @Test fun invalidMirrorMetadataUsesGitHubButStillTriesMirrorForTheApk() = runBlocking {
        respond("<html>暂不可用</html>")
        respond(githubMetadata())
        respond(payload)
        val actual = updates.latest("0.9.9")
        val tmp = folder.newFile()
        updates.download(actual, tmp) {}
        assertEquals(payload, tmp.readText())
        assertEquals(listOf(mirrorApi, githubApi, mirrorApk), paths())
    }

    @Test fun laggingMirrorCanDiscoverANewerOfficialVersion() = runBlocking {
        val newer = release.copy(version = "1.0.1")
        respond(giteeMetadata())
        respond(githubMetadata(newer))
        assertEquals(newer, updates.latest("1.0.0"))
        assertEquals(listOf(mirrorApi, githubApi), paths())
    }

    @Test fun knownMirrorVersionRemainsAvailableWhenGitHubIsUnreachable() = runBlocking {
        respond(giteeMetadata())
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(release, updates.latest("1.0.0"))
        assertEquals(listOf(mirrorApi, githubApi), paths())
    }

    @Test fun corruptMirrorBytesAreDiscardedAndProgressRestartsOnGitHub() = runBlocking {
        respond(payload.reversed())
        respond(payload)
        val tmp = folder.newFile()
        val bytes = mutableListOf<Long>()
        updates.download(release, tmp, bytes::add)
        assertEquals(payload, tmp.readText())
        assertEquals(listOf(release.size, 0L, release.size), bytes)
        assertEquals(listOf(mirrorApk, githubApk), paths())
    }

    @Test fun neitherARejectedRedirectNorAnUnverifiedFallbackCanCompleteTheDownload() = runBlocking {
        redirect("https://gitee.com/another/project/attach_files/123/download/${release.fileName}")
        respond("<html>login</html>")
        val tmp = folder.newFile()
        val failed = runCatching { updates.download(release, tmp) {} }
        assertTrue(failed.exceptionOrNull() is UpdateError)
        assertEquals(listOf(mirrorApk, githubApk), paths())
    }

    @Test fun metadataRedirectCannotContactAnAssetHost() = runBlocking {
        redirect("https://foruda.gitee.com/untrusted.json")
        respond(githubMetadata())
        assertEquals(release, updates.latest("0.9.9"))
        assertEquals(listOf(mirrorApi, githubApi), paths())
    }

    @Test fun cancelledMirrorDownloadDoesNotStartAFallbackRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val tmp = folder.newFile()
        val job = launch(Dispatchers.IO) { updates.download(release, tmp) {} }
        assertEquals(mirrorApk, server.takeRequest(3, TimeUnit.SECONDS)?.path)
        job.cancelAndJoin()
        assertEquals(1, server.requestCount)
    }

    @Test fun buildWithoutMirrorUsesOnlyTheOfficialRelease() = runBlocking {
        respond(githubMetadata())
        respond(payload)
        val network = UpdateNetwork("", client)
        val actual = network.latest("0.9.9")
        network.download(actual, folder.newFile()) {}
        assertEquals(listOf(githubApi, githubApk), paths())
    }
}
