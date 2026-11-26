package app.tingyu.update

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.BuildConfig
import java.io.File
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

    @Test fun installerProviderPermitsOnlyTheDedicatedCacheDirectory() {
        val authority = app.packageName + ".updates"
        @Suppress("DEPRECATION")
        val provider = app.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val permitted = File(app.cacheDir, "app-updates/test.apk")
        val uri = FileProvider.getUriForFile(app, authority, permitted)
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        listOf(File(app.filesDir, "private.apk"), File(app.cacheDir, "outside.apk"),
            File(app.cacheDir, "app-updates/../outside.apk")).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, authority, file) }
        }
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
}
