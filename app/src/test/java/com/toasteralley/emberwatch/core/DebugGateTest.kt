package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugGateTest {
    private class FakeClock(var t: Long = 0) : Clock { override fun now() = t }

    @Test fun releaseBuildNeverOpens() {
        assertFalse(DebugGate.canOpen(isDebugBuild = false, gestureCompleted = true))
        assertTrue(DebugGate.canOpen(isDebugBuild = true, gestureCompleted = true))
        val s = SaveData().apply { debugGodMode = true; debugBypassGates = true; debugStartWave = 500 }
        assertFalse(DebugGate.godMode(false, s)); assertFalse(DebugGate.bypassGates(false, s))
        assertEquals(1, DebugGate.startWave(false, s))
        assertTrue(DebugGate.godMode(true, s)); assertEquals(500, DebugGate.startWave(true, s))
    }

    @Test fun gestureRequiresLongPressThenFiveQuickTaps() {
        val clock = FakeClock()
        val g = SecretGesture(clock)
        repeat(5) { assertFalse(g.onTitleTap()) }            // not armed
        g.onSettingsHeld(1_000)                               // too short
        repeat(5) { assertFalse(g.onTitleTap()) }
        g.onSettingsHeld(2_000)
        repeat(4) { clock.t += 300; assertFalse(g.onTitleTap()) }
        clock.t += 300; assertTrue(g.onTitleTap())
        // taps too slow
        g.onSettingsHeld(2_500)
        var opened = false
        repeat(5) { clock.t += 900; opened = opened || g.onTitleTap() }
        assertFalse(opened)
    }

    @Test fun releaseStubIsCompiledOut() {
        // Debug unit tests compile the debug source set, where DebugTools is available;
        // the release source set provides a stub with AVAILABLE = false (see src/release).
        assertTrue(com.toasteralley.emberwatch.debug.DebugTools.AVAILABLE ==
            com.toasteralley.emberwatch.BuildConfig.DEBUG)
    }
}
