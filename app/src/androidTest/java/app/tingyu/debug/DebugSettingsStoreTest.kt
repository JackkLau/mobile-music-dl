package app.tingyu.debug

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.data.bili.UserAgentProfile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugSettingsStoreTest {
    @Test fun changesPersistWhileCapturedProfileRemainsStableAndReleaseIgnoresOverrides() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "debug-settings-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(name, mode)
        }
        try {
            val store = DebugSettingsStore(context, available = true)
            store.update { it.copy(enabled = true, parseUa = UserAgentProfile.MOBILE, audioUa = AudioUaMode.FOLLOW_PARSE) }
            val runningDownload = store.snapshot()
            assertEquals(UserAgentProfile.MOBILE, runningDownload.audioUa)
            assertEquals(runningDownload, DebugSettingsStore(context, available = true).snapshot())
            store.update { it.copy(parseUa = UserAgentProfile.DESKTOP, audioUa = AudioUaMode.DESKTOP, loggingEnabled = false) }
            assertEquals(UserAgentProfile.MOBILE, runningDownload.parseUa)
            assertEquals(UserAgentProfile.MOBILE, runningDownload.audioUa)
            assertEquals(UserAgentProfile.DESKTOP, store.snapshot().audioUa)
            val release = DebugSettingsStore(context, available = false).snapshot()
            assertEquals(UserAgentProfile.DESKTOP, release.parseUa)
            assertFalse(release.loggingEnabled)
            store.update { it.copy(enabled = false) }
            assertFalse(DebugSettingsStore(context, available = true).state.value.enabled)
            assertFalse(store.snapshot().loggingEnabled)
        } finally { base.deleteSharedPreferences(name) }
    }
}
