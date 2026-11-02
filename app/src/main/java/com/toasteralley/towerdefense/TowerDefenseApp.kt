package com.toasteralley.towerdefense

import android.app.Application
import android.content.Context
import com.toasteralley.towerdefense.ads.AdManager
import com.toasteralley.towerdefense.billing.BillingManager
import com.toasteralley.towerdefense.billing.EntitlementStore

class TowerDefenseApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var billing: BillingManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        billing = BillingManager(this, EntitlementStore(this))
        // Connects and restores purchases on startup.
        billing.startConnection()
        AdManager.initialize(this)
    }
}

val Context.app: TowerDefenseApp get() = applicationContext as TowerDefenseApp
