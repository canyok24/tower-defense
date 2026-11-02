package com.toasteralley.towerdefense

import android.app.Application
import android.content.Context
import com.toasteralley.towerdefense.ads.AdManager
import com.toasteralley.towerdefense.billing.BillingManager
import com.toasteralley.towerdefense.billing.EntitlementStore
import com.toasteralley.towerdefense.meta.PrefsKeyValueStore
import com.toasteralley.towerdefense.meta.Progression

/** Application class for The Last Wall: settings, meta progression, billing and ads. */
class LastWallApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var billing: BillingManager
        private set
    /** Tokens, meta upgrades and Super Token prestige (own prefs file). */
    lateinit var progression: Progression
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        progression = Progression(PrefsKeyValueStore(getSharedPreferences("meta_progress", MODE_PRIVATE)))
        migrateBestWave()
        billing = BillingManager(this, EntitlementStore(this))
        // Connects and restores purchases on startup.
        billing.startConnection()
        AdManager.initialize(this)
    }

    /** Older versions kept "best_wave" in the settings file; move it once. */
    private fun migrateBestWave() {
        val old = getSharedPreferences("settings", MODE_PRIVATE)
        if (old.contains("best_wave")) {
            val v = old.getInt("best_wave", 0)
            val metaPrefs = getSharedPreferences("meta_progress", MODE_PRIVATE)
            if (v > metaPrefs.getInt("best_wave", 0)) metaPrefs.edit().putInt("best_wave", v).apply()
            old.edit().remove("best_wave").apply()
        }
    }
}

val Context.app: LastWallApp get() = applicationContext as LastWallApp
