package app.tingyu.debug

import app.tingyu.data.bili.UserAgentProfile
import org.junit.Assert.*
import org.junit.Test

class DebugSettingsTest {
    @Test fun defaultRequestsUseDesktopAndDoNotLog() {
        val profile = DebugSettings().profile(available = true)
        assertEquals(UserAgentProfile.DESKTOP, profile.parseUa)
        assertEquals(UserAgentProfile.DESKTOP, profile.audioUa)
        assertFalse(profile.loggingEnabled)
    }

    @Test fun disabledModeIgnoresOldMobileOverrides() {
        val profile = DebugSettings(parseUa = UserAgentProfile.MOBILE, audioUa = AudioUaMode.MOBILE).profile(true)
        assertEquals(UserAgentProfile.DESKTOP, profile.parseUa)
        assertEquals(UserAgentProfile.DESKTOP, profile.audioUa)
        assertFalse(profile.loggingEnabled)
    }

    @Test fun releaseBuildIgnoresPersistedEnabledMode() {
        val profile = DebugSettings(true, UserAgentProfile.MOBILE, AudioUaMode.MOBILE, true).profile(false)
        assertEquals(UserAgentProfile.DESKTOP, profile.parseUa)
        assertEquals(UserAgentProfile.DESKTOP, profile.audioUa)
        assertFalse(profile.loggingEnabled)
    }

    @Test fun changingParseUaDoesNotChangeDefaultAudioUa() {
        val profile = DebugSettings(enabled = true, parseUa = UserAgentProfile.MOBILE).profile(true)
        assertEquals(UserAgentProfile.MOBILE, profile.parseUa)
        assertEquals(UserAgentProfile.DESKTOP, profile.audioUa)
    }

    @Test fun followParseAndExplicitAudioHaveDifferentMeaning() {
        val settings = DebugSettings(true, UserAgentProfile.MOBILE, AudioUaMode.FOLLOW_PARSE, false)
        assertEquals(UserAgentProfile.MOBILE, settings.profile(true).audioUa)
        assertFalse(settings.profile(true).loggingEnabled)
        assertEquals(UserAgentProfile.DESKTOP, settings.copy(audioUa = AudioUaMode.DESKTOP).profile(true).audioUa)
    }

    @Test fun capturedProfileDoesNotChangeWithSettings() {
        val settings = DebugSettings(enabled = true)
        val captured = settings.profile(true)
        val changed = settings.copy(audioUa = AudioUaMode.MOBILE).profile(true)
        assertEquals(UserAgentProfile.DESKTOP, captured.audioUa)
        assertEquals(UserAgentProfile.MOBILE, changed.audioUa)
    }

    @Test fun sevenConsecutiveTapsUnlockAndThenReset() {
        val gate = DebugModeGate(true)
        for (tap in 1..6) assertEquals(7 - tap, gate.tap(tap * 100L))
        assertEquals(0, gate.tap(700))
        assertEquals(6, gate.tap(800))
    }

    @Test fun longGapAndNavigationResetUnlockGesture() {
        val gate = DebugModeGate(true)
        repeat(6) { gate.tap(it * 100L) }
        assertEquals(6, gate.tap(3_000))
        gate.reset()
        assertEquals(6, gate.tap(3_100))
    }

    @Test fun releaseCannotUnlock() {
        val gate = DebugModeGate(false)
        repeat(20) { assertNull(gate.tap(it * 100L)) }
    }
}
