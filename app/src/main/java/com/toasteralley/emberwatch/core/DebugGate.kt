package com.toasteralley.emberwatch.core

/**
 * Management override gate. The override menu itself only exists in the debug
 * source set; this gate is the second lock: it refuses whenever the build is
 * not a debug build, regardless of gestures or saved debug flags.
 */
object DebugGate {
    fun canOpen(isDebugBuild: Boolean, gestureCompleted: Boolean) = isDebugBuild && gestureCompleted
    /** Saved debug flags (god mode, bypass gates, start wave) only apply in debug builds. */
    fun godMode(isDebugBuild: Boolean, save: SaveData) = isDebugBuild && save.debugGodMode
    fun bypassGates(isDebugBuild: Boolean, save: SaveData) = isDebugBuild && save.debugBypassGates
    fun researchTabUnlocked(isDebugBuild: Boolean, save: SaveData) = isDebugBuild && save.debugResearchTabUnlocked
    fun startWave(isDebugBuild: Boolean, save: SaveData) = if (isDebugBuild) save.debugStartWave.coerceAtLeast(1) else 1
}

/**
 * Secret gesture: long-press the settings icon (~2 s) to arm, then tap the
 * title 5 times within 3 s (within 10 s of arming).
 */
class SecretGesture(private val clock: Clock = SystemClock) {
    companion object {
        const val LONG_PRESS_MS = 2_000L
        const val ARM_WINDOW_MS = 10_000L
        const val TAPS = 5
        const val TAP_WINDOW_MS = 3_000L
    }
    private var armedAt = -1L
    private val taps = ArrayDeque<Long>()

    /** Call with how long the settings icon was held. */
    fun onSettingsHeld(heldMs: Long) {
        if (heldMs >= LONG_PRESS_MS) { armedAt = clock.now(); taps.clear() }
    }

    /** Returns true when the pattern completes (and resets). */
    fun onTitleTap(): Boolean {
        val now = clock.now()
        if (armedAt < 0 || now - armedAt > ARM_WINDOW_MS) { armedAt = -1; taps.clear(); return false }
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > TAP_WINDOW_MS) taps.removeFirst()
        if (taps.size >= TAPS) { armedAt = -1; taps.clear(); return true }
        return false
    }
}
