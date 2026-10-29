package app.tingyu.debug

import app.tingyu.data.bili.NetworkProfile
import app.tingyu.data.bili.UserAgentProfile

enum class AudioUaMode(val label: String) {
    DESKTOP("桌面"), MOBILE("移动"), FOLLOW_PARSE("跟随解析");
}

/** Only explicit developer mode may override the verified production profile. */
data class DebugSettings(
    val enabled: Boolean = false,
    val parseUa: UserAgentProfile = UserAgentProfile.DESKTOP,
    val audioUa: AudioUaMode = AudioUaMode.DESKTOP,
    val loggingEnabled: Boolean = true,
) {
    fun profile(available: Boolean): NetworkProfile {
        if (!available || !enabled) return NetworkProfile()
        val media = when (audioUa) {
            AudioUaMode.DESKTOP -> UserAgentProfile.DESKTOP
            AudioUaMode.MOBILE -> UserAgentProfile.MOBILE
            AudioUaMode.FOLLOW_PARSE -> parseUa
        }
        return NetworkProfile(parseUa, media, loggingEnabled)
    }
}

/** Uses monotonic time. Gaps reset the gesture; release builds cannot unlock it. */
class DebugModeGate(private val available: Boolean, private val timeoutMs: Long = 2_000) {
    private var taps = 0
    private var lastTap: Long? = null

    fun tap(nowMs: Long): Int? {
        if (!available) return null
        val previous = lastTap
        if (previous == null || nowMs < previous || nowMs - previous > timeoutMs) taps = 0
        lastTap = nowMs
        taps++
        val remaining = 7 - taps
        if (remaining == 0) reset()
        return remaining
    }

    fun reset() { taps = 0; lastTap = null }
}
