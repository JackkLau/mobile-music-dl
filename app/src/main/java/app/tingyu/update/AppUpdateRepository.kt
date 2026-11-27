package app.tingyu.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.tingyu.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class AppUpdateRepository(private val context: Context) {
    private val directory = File(context.cacheDir, "app-updates")
    private val network = UpdateNetwork(BuildConfig.GITEE_REPOSITORY)

    suspend fun latest(): GitHubRelease = network.latest(BuildConfig.VERSION_NAME)

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

    fun verifiedInstallIntent(release: GitHubRelease): android.content.Intent {
        when (verifiedCache(release)) {
            true -> return systemInstaller(context, file(release), release.fileName)
            false -> throw UpdateError("正式包与当前签名或版本不兼容，无法覆盖安装")
            null -> throw UpdateError("安装包已变更或缓存已清理，请重新下载")
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
            network.download(release, temporary, progress)
            val compatible = canInstall(release, temporary)
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(target)) throw UpdateError("无法保存安装包，请检查可用空间后重试")
            directory.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach(File::delete)
            return compatible
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val discardOutput = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}
