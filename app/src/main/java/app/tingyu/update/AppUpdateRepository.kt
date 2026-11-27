package app.tingyu.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val RELEASE_SIGNER = "ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911"

internal class AppUpdateRepository(private val context: Context) {
    private val directory = File(context.cacheDir, "app-updates")
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            var request = chain.request()
            var response = chain.proceed(request)
            var redirects = 0
            while (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                val next = location?.let(request.url::resolve)
                if (request.url.toString() == RELEASE_API || ++redirects > 4 || next == null || !allowedAssetRedirect(next)) {
                    throw UpdateError("安装包跳转地址异常，请稍后再试")
                }
                request = Request.Builder().url(next).build()
                response = chain.proceed(request)
            }
            response
        }.build()

    suspend fun latest(): GitHubRelease = read(RELEASE_API) { body ->
        val source = body.source()
        if (source.request(1024L * 1024 + 1)) throw UpdateError("版本信息过大，请稍后再试")
        GitHubRelease.parse(source.readUtf8())
    }

    fun file(release: GitHubRelease) = File(directory, release.sha256 + ".apk")

    fun canInstall(release: GitHubRelease, apk: File = file(release)): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info == null || info.packageName != context.packageName || info.versionName != release.version ||
            signer(info) != RELEASE_SIGNER) throw UpdateError("安装包身份或签名校验失败，请重新下载")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return signer(installed) == RELEASE_SIGNER && info.longVersionCode >= installed.longVersionCode
    }

    private fun signer(info: PackageInfo) = info.signingInfo?.apkContentsSigners
        ?.singleOrNull()?.toByteArray()?.toByteString()?.sha256()?.hex()

    fun verifiedCache(release: GitHubRelease): Boolean? {
        val target = file(release)
        if (!target.isFile) return null
        return try {
            target.inputStream().use { copyVerifiedApk(it, discardOutput, release) {} }
            canInstall(release)
        } catch (_: IOException) {
            target.delete()
            null
        }
    }

    fun exportVerified(release: GitHubRelease, openDestination: () -> OutputStream) {
        // A cache check at download time can precede the user's export by several days.
        if (verifiedCache(release) == null) throw UpdateError("安装包已变更或缓存已清理，请重新下载")
        file(release).inputStream().use { source ->
            openDestination().use { output -> copyVerifiedApk(source, output, release) {} }
        }
    }

    suspend fun download(release: GitHubRelease, progress: (Long) -> Unit): Boolean {
        verifiedCache(release)?.let { return it }
        currentCoroutineContext().ensureActive()
        directory.mkdirs()
        val target = file(release)
        val temporary = File.createTempFile("update-", ".apk", context.cacheDir)
        try {
            temporary.outputStream().use { output ->
                read(release.url) { body ->
                    val length = body.contentLength()
                    if (length >= 0 && length != release.size) throw UpdateError("安装包大小不符，请重新下载")
                    copyVerifiedApk(body.byteStream(), output, release, progress)
                }
            }
            val compatible = canInstall(release, temporary)
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(target)) throw UpdateError("无法保存安装包，请检查可用空间后重试")
            directory.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach(File::delete)
            return compatible
        } finally {
            temporary.delete()
        }
    }

    private suspend fun <T> read(url: String, consume: (ResponseBody) -> T): T = suspendCancellableCoroutine { result ->
        val call = client.newCall(Request.Builder().url(url).build())
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (result.isActive) result.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (it.code != 200) throw UpdateError("GitHub 返回 HTTP ${it.code}，请稍后重试")
                        consume(it.body ?: throw UpdateError("服务器未返回内容，请重新下载"))
                    }
                    if (result.isActive) result.resume(value)
                } catch (error: Exception) {
                    if (result.isActive) result.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        private val discardOutput = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
