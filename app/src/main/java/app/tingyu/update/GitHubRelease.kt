package app.tingyu.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val RELEASE_API = "https://api.github.com/repos/JackkLau/mobile-music-dl/releases/latest"
private const val DOWNLOAD_ROOT = "https://github.com/JackkLau/mobile-music-dl/releases/download"
private val stableVersion = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")
private val digestFormat = Regex("[a-f0-9]{64}")

internal class UpdateError(message: String) : IOException(message)

internal data class GitHubRelease(val version: String, val size: Long, val sha256: String) {
    init {
        require(stableVersion.matches(version) && size in 1..256L * 1024 * 1024 && digestFormat.matches(sha256))
    }

    val fileName get() = "tingyu-${version}-release.apk"
    val url get() = "$DOWNLOAD_ROOT/v$version/$fileName"

    fun compareToInstalled(installed: String): Int {
        val local = installed.split('.').map(String::toInt)
        version.split('.').map(String::toInt).forEachIndexed { index, part ->
            val order = part.compareTo(local[index])
            if (order != 0) return order
        }
        return 0
    }

    companion object {
        fun parse(response: String): GitHubRelease {
            try {
                val release = Json.parseToJsonElement(response).jsonObject
                require(!release.getValue("draft").jsonPrimitive.boolean && !release.getValue("prerelease").jsonPrimitive.boolean)
                val tag = release.getValue("tag_name").jsonPrimitive.content
                require(tag.startsWith("v"))
                val version = tag.substring(1)
                require(stableVersion.matches(version))
                val asset = release.getValue("assets").jsonArray.map { it.jsonObject }.single {
                    it["name"]?.jsonPrimitive?.content == "tingyu-${version}-release.apk"
                }
                val digest = asset.getValue("digest").jsonPrimitive.content
                require(digest.startsWith("sha256:"))
                val apk = GitHubRelease(version, asset.getValue("size").jsonPrimitive.long, digest.substringAfter(':'))
                require(asset.getValue("browser_download_url").jsonPrimitive.content == apk.url)
                return apk
            } catch (_: RuntimeException) {
                throw UpdateError("此版本缺少可验证的正式安装包，请稍后再试")
            }
        }
    }
}

internal fun allowedAssetRedirect(url: HttpUrl): Boolean =
    url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun copyVerifiedApk(input: InputStream, output: OutputStream, release: GitHubRelease, progress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    var reported = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        if (count > release.size) throw UpdateError("安装包大小不符，请重新下载")
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        if (count - reported >= 256 * 1024 || count == release.size) {
            progress(count)
            reported = count
        }
    }
    if (count != release.size || digest.digest().toByteString().hex() != release.sha256) {
        throw UpdateError("安装包完整性校验失败，请重新下载")
    }
}
