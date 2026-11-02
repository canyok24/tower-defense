package com.toasteralley.towerdefense

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = prefs.edit { putBoolean("sound", value) }

    var bestWave: Int
        get() = prefs.getInt("best_wave", 0)
        set(value) = prefs.edit { putInt("best_wave", value) }
}
