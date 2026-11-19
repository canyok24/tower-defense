package com.toasteralley.emberwatch

import android.app.Application
import com.toasteralley.emberwatch.ads.AdManager
import com.toasteralley.emberwatch.billing.BillingManager
import com.toasteralley.emberwatch.billing.EntitlementStore
import com.toasteralley.emberwatch.core.BountyMultipliers
import com.toasteralley.emberwatch.core.DebugGate
import com.toasteralley.emberwatch.core.Products
import com.toasteralley.emberwatch.core.ResearchBay
import com.toasteralley.emberwatch.core.SaveData
import com.toasteralley.emberwatch.data.SaveStore

/** Process-wide singletons: the save, billing and ads. */
class EmberwatchApp : Application() {
    lateinit var saveStore: SaveStore; private set
    lateinit var save: SaveData; private set
    lateinit var billing: BillingManager; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        saveStore = SaveStore(this)
        save = saveStore.load()
        billing = BillingManager(this, EntitlementStore(this))
        billing.startConnection()
        AdManager.initialize(this)
    }

    fun persist() = saveStore.save(save)
    val owned: Set<String> get() = billing.owned.value
    val noAds: Boolean get() = owned.any { id -> Products.byId(id)?.removesAds == true }
    val bypassGates: Boolean get() = DebugGate.bypassGates(BuildConfig.DEBUG, save)
    /** Bounty multiplier passed to the engine (Bounty Gain is applied by the engine from workshop levels). */
    fun bountyMultiplier(now: Long = System.currentTimeMillis()) = BountyMultipliers.total(save, owned, now)
    /** For menus: includes the permanent Ledger Bounty Gain level. */
    fun displayBountyMultiplier(now: Long = System.currentTimeMillis()) =
        bountyMultiplier(now) * com.toasteralley.emberwatch.core.Upgrade.BOUNTY_GAIN.value(save.level(com.toasteralley.emberwatch.core.Upgrade.BOUNTY_GAIN))
    fun research() = ResearchBay(save)

    /** Completes finished research timers (wall clock) and persists if anything changed. */
    fun tickResearch(): Boolean { val done = research().tick(); if (done.isNotEmpty()) persist(); return done.isNotEmpty() }

    companion object { lateinit var instance: EmberwatchApp; private set }
}
