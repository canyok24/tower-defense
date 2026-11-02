package com.toasteralley.towerdefense

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Minimal sound effects using system tones (no audio assets needed).
 * Swap for SoundPool + your own .ogg files for real SFX.
 */
class SoundManager(private val settings: SettingsStore) {
    enum class Sfx(val tone: Int, val durationMs: Int) {
        PLACE(ToneGenerator.TONE_PROP_BEEP, 60),
        UPGRADE(ToneGenerator.TONE_PROP_ACK, 120),
        SELL(ToneGenerator.TONE_PROP_BEEP2, 80),
        LIFE_LOST(ToneGenerator.TONE_PROP_NACK, 120),
        WAVE(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200),
    }

    private var tone: ToneGenerator? = null

    fun play(sfx: Sfx) {
        if (!settings.soundEnabled) return
        try {
            val gen = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 40).also { tone = it }
            gen.startTone(sfx.tone, sfx.durationMs)
        } catch (e: RuntimeException) {
            Log.w("SoundManager", "Tone failed", e)
        }
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
