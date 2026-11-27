package app.tingyu.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.BuildConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatePackageIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = instrumentation.targetContext

    @Test fun rejectsDebugSignerAndWrongPackageUsingActualInstalledArchives() {
        val repo = AppUpdateRepository(app)
        val debugApk = File(app.applicationInfo.sourceDir)
        val release = GitHubRelease(BuildConfig.VERSION_NAME, debugApk.length(), "0".repeat(64))
        assertThrows(UpdateError::class.java) { repo.canInstall(release, debugApk) }
        val otherApk = File(instrumentation.context.applicationInfo.sourceDir)
        @Suppress("DEPRECATION")
        val other = app.packageManager.getPackageArchiveInfo(otherApk.path, 0)!!
        assertNotEquals(app.packageName, other.packageName)
        assertThrows(UpdateError::class.java) { repo.canInstall(release, otherApk) }
    }

    @Test fun applicationDoesNotRequestAnInstallerPermissionOrExposeCachedApks() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES))
        @Suppress("DEPRECATION")
        val provider = app.packageManager.resolveContentProvider(app.packageName + ".updates", 0)
        assertNull(provider)
    }

    @Test fun verifiedReleasePackageCanBeDownloadedButNotInstalledOverDebug() {
        // A previous public GitHub release may be staged by the device test runner.
        val fixture = File(app.cacheDir, "update-test-release.apk")
        assumeTrue("Optional original signed APK fixture was not supplied", fixture.isFile)
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageArchiveInfo(fixture.absolutePath, 0)!!
        val release = GitHubRelease(info.versionName!!, fixture.length(), "0".repeat(64))
        val repo = AppUpdateRepository(app)
        assertFalse(repo.canInstall(release, fixture))
        assertThrows(UpdateError::class.java) { repo.canInstall(release.copy(version = "99.99.99"), fixture) }
    }

    @Test fun exportRechecksTheExactSignedBytesBeforeOpeningTheDestination() {
        val fixture = File(app.cacheDir, "update-test-release.apk")
        assumeTrue("Original signed APK fixture was not supplied", fixture.isFile)
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageArchiveInfo(fixture.absolutePath, 0)!!
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                var size = input.read(buffer)
                while (size >= 0) {
                    sha.update(buffer, 0, size)
                    size = input.read(buffer)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        val release = GitHubRelease(info.versionName!!, fixture.length(), digest(fixture))
        val repo = AppUpdateRepository(app)
        val cache = repo.file(release)
        cache.parentFile!!.mkdirs()
        val exported = File.createTempFile("verified-copy-", ".apk", app.cacheDir)
        try {
            fixture.copyTo(cache, overwrite = true)
            repo.exportVerified(release) { exported.outputStream() }
            assertEquals(release.sha256, digest(exported))
            java.io.RandomAccessFile(cache, "rw").use { it.write(0) }
            assertThrows(UpdateError::class.java) {
                repo.exportVerified(release) { throw AssertionError("An invalid cache must not open or truncate the destination") }
            }
            assertFalse(cache.exists())
            assertEquals(release.sha256, digest(exported))
        } finally {
            exported.delete()
            cache.delete()
        }
    }
}
