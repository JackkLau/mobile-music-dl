package app.tingyu.debug

import android.content.Context
import app.tingyu.data.bili.UserAgentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class DebugSettingsStore(context: Context, val available: Boolean) {
    private val preferences = context.applicationContext.getSharedPreferences("developer-settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    fun snapshot() = mutable.value.profile(available)

    suspend fun update(transform: (DebugSettings) -> DebugSettings) = mutex.withLock {
        if (!available) return@withLock
        withContext(Dispatchers.IO) {
            val changed = transform(mutable.value)
            // Reset overrides when disabled; stale test settings cannot affect later downloads.
            val next = if (changed.enabled) changed else DebugSettings()
            val saved = preferences.edit()
                .putBoolean("enabled", next.enabled)
                .putString("parse_ua", next.parseUa.name)
                .putString("audio_ua", next.audioUa.name)
                .putBoolean("logging", next.loggingEnabled)
                .commit()
            if (!saved) throw IOException("无法保存调试设置，请检查手机存储空间")
            mutable.value = next
        }
    }

    private fun read(): DebugSettings {
        if (!available || !preferences.getBoolean("enabled", false)) return DebugSettings()
        return DebugSettings(
            enabled = true,
            parseUa = UserAgentProfile.entries.firstOrNull { it.name == preferences.getString("parse_ua", null) }
                ?: UserAgentProfile.DESKTOP,
            audioUa = AudioUaMode.entries.firstOrNull { it.name == preferences.getString("audio_ua", null) }
                ?: AudioUaMode.DESKTOP,
            loggingEnabled = preferences.getBoolean("logging", true),
        )
    }
}
