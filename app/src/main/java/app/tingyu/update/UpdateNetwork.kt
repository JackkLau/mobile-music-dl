package app.tingyu.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class UpdateNetwork(
    private val mirrorRepository: String,
    private val client: OkHttpClient = releaseClient(),
) {
    private val mirrorApi = "https://gitee.com/api/v5/repos/$mirrorRepository/releases/latest"

    suspend fun latest(installedVersion: String): GitHubRelease {
        val mirror = if (mirrorRepository.isEmpty()) null else try {
            metadata(mirrorApi) { GitHubRelease.parseGitee(it, mirrorRepository) }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            null
        }
        if (mirror != null && mirror.compareToInstalled(installedVersion) > 0) return mirror

        // A reachable but lagging mirror must not indefinitely hide a GitHub release.
        val github = try {
            metadata(RELEASE_API, timeoutSeconds = if (mirror == null) 8 else 3, parse = GitHubRelease::parse)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return mirror ?: throw e
        }
        return if (mirror != null && mirror.compareToInstalled(github.version) > 0) mirror else github
    }

    private suspend fun metadata(url: String, timeoutSeconds: Long = 8, parse: (String) -> GitHubRelease) = read(url, timeoutSeconds) { body ->
        val source = body.source()
        if (source.request(1024L * 1024 + 1)) throw UpdateError("版本信息过大，请稍后再试")
        parse(source.readUtf8())
    }

    suspend fun download(release: GitHubRelease, temporary: File, progress: (Long) -> Unit) {
        if (mirrorRepository.isNotEmpty() && !release.legacy) {
            try {
                fetchApk(release.giteeUrl(mirrorRepository), release, temporary, progress)
                return
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                progress(0)
            }
        }
        fetchApk(release.url, release, temporary, progress)
    }

    private suspend fun fetchApk(url: String, release: GitHubRelease, temporary: File, progress: (Long) -> Unit) {
        // Truncate the partial bytes from a failed attempt before trying the other host.
        temporary.outputStream().use { output ->
            read(url) { body ->
                val length = body.contentLength()
                if (length >= 0 && length != release.size) throw UpdateError("安装包大小不符，请重新下载")
                copyVerifiedApk(body.byteStream(), output, release, progress)
            }
        }
    }

    private suspend fun <T> read(url: String, timeoutSeconds: Long? = null, consume: (ResponseBody) -> T): T =
        suspendCancellableCoroutine { result ->
            val call = client.newCall(Request.Builder().url(url).build())
            if (timeoutSeconds != null) call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (result.isActive) result.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code != 200) {
                                val site = if (call.request().url.host == "gitee.com") "Gitee" else "GitHub"
                                throw UpdateError("$site 返回 HTTP ${it.code}，请稍后重试")
                            }
                            consume(it.body ?: throw UpdateError("服务器未返回内容，请重新下载"))
                        }
                        if (result.isActive) result.resume(value)
                    } catch (error: Exception) {
                        if (result.isActive) result.resumeWithException(error)
                    }
                }
            })
        }
}

internal fun releaseClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES)
    .addInterceptor { chain ->
        val initial = chain.request().url
        var request = chain.request()
        var response = chain.proceed(request)
        var redirects = 0
        while (response.isRedirect) {
            val next = response.header("Location")?.let(request.url::resolve)
            response.close()
            if (++redirects > 4 || next == null || !allowedReleaseRedirect(initial, next)) {
                throw UpdateError("安装包跳转地址异常，请稍后再试")
            }
            request = Request.Builder().url(next).build()
            response = chain.proceed(request)
        }
        response
    }.build()

private fun allowedReleaseRedirect(initial: HttpUrl, next: HttpUrl): Boolean {
    if (initial.host == "github.com") return allowedAssetRedirect(next)
    val source = initial.pathSegments
    if (initial.host != "gitee.com" || source.size != 6 || source[2] != "releases" || source[3] != "download") return false
    if (next.scheme != "https" || next.port != 443 || next.username.isNotEmpty() || next.password.isNotEmpty()) return false
    val path = next.pathSegments
    if (next.host == "foruda.gitee.com") {
        return path.size == 3 && path[0] == "attach_file" && path[1].matches(Regex("[0-9]+")) && path[2] == source.last()
    }
    return next.host == "gitee.com" && path.size == 6 && path.take(2) == source.take(2) &&
        path[2] == "attach_files" && path[3].matches(Regex("[0-9]+")) && path[4] == "download" && path[5] == source.last()
}
