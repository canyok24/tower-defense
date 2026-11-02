package com.toasteralley.towerdefense

import android.content.Context
import com.toasteralley.towerdefense.meta.KeyValueStore
import com.toasteralley.towerdefense.meta.PrefsKeyValueStore

/** User settings. Never touched by a prestige reset. */
class SettingsStore(private val prefs: KeyValueStore) {
    constructor(context: Context) :
        this(PrefsKeyValueStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = prefs.putBoolean("sound", value)
}
