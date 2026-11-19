package com.toasteralley.emberwatch.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.toasteralley.emberwatch.BuildConfig
import com.toasteralley.emberwatch.ads.AdManager
import com.toasteralley.emberwatch.core.CoinMultipliers
import com.toasteralley.emberwatch.core.DiamondPerk
import com.toasteralley.emberwatch.core.EnemyPool
import com.toasteralley.emberwatch.core.Enhancement
import com.toasteralley.emberwatch.core.Gates
import com.toasteralley.emberwatch.core.NumberFormat
import com.toasteralley.emberwatch.core.Products
import com.toasteralley.emberwatch.core.ResearchBay
import com.toasteralley.emberwatch.core.ResearchProject
import com.toasteralley.emberwatch.core.SaveData
import com.toasteralley.emberwatch.core.TowerStats
import com.toasteralley.emberwatch.core.UltimateWeapon
import com.toasteralley.emberwatch.core.Upgrade
import com.toasteralley.emberwatch.core.Workshop
import com.toasteralley.emberwatch.core.WorkshopTab
import kotlinx.coroutines.launch

private fun c(v: Double) = NumberFormat.compact(v)

/**
 * 1. Workshop: three tabs (ASSAULT / SHIELD / LEDGER). Coins buy permanent levels
 * here; the in-run panel sells temporary levels of the same upgrades for Cash.
 */
class WorkshopActivity : ListScreenActivity() {
    override val screenTitle = "Workshop"
    private var tab = WorkshopTab.ASSAULT

    override fun buildRows() {
        list.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            for (t in WorkshopTab.entries) addView(emberButton(t.label, t != tab) { tab = t; build() },
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        })
        val s = TowerStats.resolve(save)
        note(when (tab) {
            WorkshopTab.ASSAULT -> "DMG ${c(s.damage)} · ${"%.2f".format(s.fireRate)}/s · Crit ${"%.1f".format(s.critChance * 100)}% x${"%.2f".format(s.critFactor)} · Range ${"%.2f".format(s.range)}"
            WorkshopTab.SHIELD -> "HP ${c(s.maxHealth)} · DEF ${"%.1f".format(s.defense * 100)}% / -${c(s.defenseAbs)} · Wall ${c(s.wallMaxHp)}"
            WorkshopTab.LEDGER -> "Coins x${"%.2f".format(s.bounty)} · Cash x${"%.2f".format(s.tribute)} · Interest ${"%.1f".format(s.interest * 100)}%"
        } + "\nPermanent levels (Coins). In runs, the same upgrades can be raised temporarily with Cash.")
        for (u in Upgrade.ofTab(tab)) {
            val lvl = save.level(u)
            if (!Workshop.gateOpen(u, save, app.bypassGates)) { addRow(u.label, "Locked: reach wave ${u.gateWave}", null); continue }
            val maxed = lvl >= u.maxLevel
            val cost = u.coinCost(lvl)
            addRow("${u.label}  Lv $lvl/${u.maxLevel}", "${u.description}\n${u.format(lvl)} → ${if (maxed) "max" else u.format(lvl + 1)}",
                if (maxed) "MAX" else "${c(cost)} coins", Workshop.canBuy(u, save, app.bypassGates)) {
                Workshop.buy(u, save, app.bypassGates)
            }
        }
    }
}

/** 2. Ultimate Weapons: unlocked/upgraded with Diamonds, auto-fire on cooldown in runs. */
class UltimatesActivity : ListScreenActivity() {
    override val screenTitle = "Ultimate Weapons"
    override fun buildRows() {
        if (!Gates.open(Gates.ULTIMATES_WAVE, save, app.bypassGates)) { note("Locked: reach wave ${Gates.ULTIMATES_WAVE}."); return }
        note("Unlocked weapons trigger automatically during runs whenever their cooldown is ready.")
        for (u in UltimateWeapon.entries) {
            val lvl = save.level(u); val cost = u.cost(lvl); val maxed = lvl >= u.maxLevel
            val detail = when (u) {
                UltimateWeapon.FROST_LATTICE -> "slow ${(u.slow(lvl.coerceAtLeast(1)) * 100).toInt()}% for ${u.duration(lvl.coerceAtLeast(1))}s"
                UltimateWeapon.CHAIN_ARC -> "${u.chainTargets(lvl.coerceAtLeast(1))} targets, x${u.damageFactor(lvl.coerceAtLeast(1))} dmg"
                else -> "x${u.damageFactor(lvl.coerceAtLeast(1))} attack damage"
            }
            addRow(if (lvl == 0) "${u.label} (locked)" else "${u.label}  Lv $lvl",
                "${u.description}\n$detail · cooldown ${"%.0f".format(u.cooldown(lvl.coerceAtLeast(1)))}s",
                if (maxed) "MAX" else if (lvl == 0) "Unlock ◆$cost" else "◆$cost", !maxed && save.diamonds >= cost) {
                if (save.diamonds >= cost) { save.diamonds -= cost; save.ultimates[u.name] = lvl + 1 }
            }
        }
    }
}

/** 3. Diamond Perks. */
class PerksActivity : ListScreenActivity() {
    override val screenTitle = "Diamond Perks"
    override fun buildRows() {
        if (!Gates.open(Gates.PERKS_WAVE, save, app.bypassGates)) { note("Locked: reach wave ${Gates.PERKS_WAVE}."); return }
        note("Diamonds come from Spiral Boss kills and first-time milestones (every 10 best-wave).")
        for (p in DiamondPerk.entries) {
            val lvl = save.level(p); val cost = p.cost(lvl); val maxed = lvl >= p.maxLevel
            addRow("${p.label}  Lv $lvl/${p.maxLevel}", p.description, if (maxed) "MAX" else "◆$cost", !maxed && save.diamonds >= cost) {
                if (save.diamonds >= cost) { save.diamonds -= cost; save.perks[p.name] = lvl + 1 }
            }
        }
    }
}

/** 4. Enhancement window: global multiplier over everything. */
class EnhancementActivity : ListScreenActivity() {
    override val screenTitle = "Enhancement"
    override fun buildRows() {
        if (!Gates.open(Gates.ENHANCEMENT_WAVE, save, app.bypassGates)) { note("Locked: reach wave ${Gates.ENHANCEMENT_WAVE}."); return }
        val lvl = save.enhancement
        note("Multiplies health, damage, crit bonuses, ultimate damage and all coin income. " +
            "Milestone bonus (+5%, compounding) every ${Enhancement.MILESTONE_EVERY} levels. Current cap: ${Enhancement.ENHANCEMENT_CAP}.")
        val maxed = lvl >= Enhancement.ENHANCEMENT_CAP
        val cost = Enhancement.cost(lvl)
        val pool = EnemyPool.allowedTiers(lvl)
        note("Enemy pool: " + pool.joinToString { it.name.lowercase().replace('_', '-') } +
            when {
                lvl < EnemyPool.DANGEROUS_UNLOCK_LEVEL -> " · dangerous shapes join at level ${EnemyPool.DANGEROUS_UNLOCK_LEVEL}"
                lvl < EnemyPool.ALL_UNLOCK_LEVEL -> " · boss-level & endgame shapes join at level ${EnemyPool.ALL_UNLOCK_LEVEL}"
                else -> ""
            })
        addRow("Level $lvl / ${Enhancement.ENHANCEMENT_CAP}",
            "x${"%.2f".format(Enhancement.multiplier(lvl))} → ${if (maxed) "cap reached" else "x${"%.2f".format(Enhancement.multiplier(lvl + 1))}"}" +
                (if (!maxed && (lvl + 1) % Enhancement.MILESTONE_EVERY == 0) "  ★ milestone" else ""),
            if (maxed) "CAP" else "${c(cost)} coins", !maxed && save.coins >= cost) {
            if (save.coins >= cost) { save.coins -= cost; save.enhancement = lvl + 1 }
        }
        section("NEXT MILESTONES")
        var m = (lvl / Enhancement.MILESTONE_EVERY + 1) * Enhancement.MILESTONE_EVERY
        repeat(2) { note("Level $m: x${"%.2f".format(Enhancement.multiplier(m))}"); m += Enhancement.MILESTONE_EVERY }
    }
}

/** 5. Research Bay: real-time timers using the wall clock. */
class ResearchActivity : ListScreenActivity() {
    override val screenTitle = "Research Bay"
    override val refreshMs = 1000L
    override fun onTick() { app.tickResearch() }
    override fun buildRows() {
        val bay = ResearchBay(save)
        note("Scientists run ${bay.slots} project(s) at once. Timers keep running while the app is closed.")
        if (save.activeResearch.isNotEmpty()) section("IN PROGRESS")
        for (a in save.activeResearch) addRow("${a.project.label} → Lv ${a.targetLevel}", "Done in ${NumberFormat.duration(bay.remainingMs(a))}", null)
        section("PROJECTS")
        val bypass = app.bypassGates
        for (p in ResearchProject.entries) {
            val lvl = save.level(p)
            if (!bay.gateOpen(p, bypass)) { addRow(p.label, "Locked: reach wave ${p.gateWave}", null); continue }
            val maxed = lvl >= p.maxLevel
            val check = bay.check(p, bypass)
            val desc = p.description + if (maxed) "\nComplete" else
                "\n${c(p.coinCost(lvl))} coins + ${p.coreCost(lvl)} cores · ${NumberFormat.duration(p.durationMs(lvl))}"
            addRow("${p.label}  Lv $lvl/${p.maxLevel}", desc,
                when { maxed -> "DONE"; check is ResearchBay.StartResult.Blocked -> check.reason; else -> "Research" },
                check == ResearchBay.StartResult.Ok) { bay.start(p, bypass) }
        }
    }
}

/** Store: IAP packs (Play Billing) + rewarded-ad boost. */
class StoreActivity : ListScreenActivity() {
    override val screenTitle = "Store"
    override val refreshMs = 1000L
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { app.billing.owned.collect { build() } }
        lifecycleScope.launch { app.billing.products.collect { build() } }
        lifecycleScope.launch { app.billing.messages.collect { toast(it) } }
        AdManager.preloadRewarded(this)
    }
    override fun buildRows() {
        val owned = app.owned
        val now = System.currentTimeMillis()
        note("All packs are permanent and stack multiplicatively with each other and with research, enhancement and boosts. " +
            "Current pack multiplier: x${"%.1f".format(CoinMultipliers.packs(owned))} · total coin multiplier x${c(app.displayCoinMultiplier(now))}")
        for (p in Products.ALL) {
            val has = p.id in owned
            val price = app.billing.priceOf(p.id)
            addRow(p.title, "x${p.coinMultiplier} coins forever" + (if (p.removesAds) " · removes banner & interstitial ads" else "") +
                (if (price == null && !has) "\nUnavailable until published on Google Play" else ""),
                if (has) "Owned" else price ?: p.listPrice, !has) { app.billing.launchPurchase(this, p.id) }
        }
        section("BOOST")
        val left = (save.boostUntilMs - now).coerceAtLeast(0)
        addRow("Watch an ad: 1.5x coins", "Adds 20 minutes (stacks up to 2 hours). " +
            if (left > 0) "Active: ${NumberFormat.duration(left)} left" else "Inactive",
            "Watch", CoinMultipliers.canAddChunk(save, now)) {
            AdManager.showRewarded(this, onReward = {
                CoinMultipliers.addRewardedChunk(save, System.currentTimeMillis()); app.persist(); build()
            }, onUnavailable = { toast("Ad not ready yet, try again shortly") })
        }
        section("PURCHASES")
        addRow("Restore purchases", "Re-sync owned packs from Google Play", "Restore") { app.billing.restorePurchasesAsync(userInitiated = true) }
    }
}

/** Settings. */
class SettingsActivity : ListScreenActivity() {
    override val screenTitle = "Settings"
    override fun buildRows() {
        addRow("Restore purchases", "Re-sync owned packs from Google Play", "Restore") { app.billing.restorePurchasesAsync(true) }
        addRow("Reset progress", "Wipes coins, upgrades, research and waves. Purchases are never wiped.", "Reset") {
            AlertDialog.Builder(this).setTitle("Reset all progress?")
                .setMessage("Your store purchases stay owned.")
                .setPositiveButton("Reset") { _, _ -> resetProgress(); app.persist(); build() }
                .setNegativeButton("Cancel", null).show()
        }
        note("Emberwatch ${BuildConfig.VERSION_NAME}")
    }
    private fun resetProgress() {
        val fresh = SaveData()
        save.coins = fresh.coins; save.diamonds = 0; save.cores = 0; save.bestWave = 0; save.totalRuns = 0
        save.selectedStage = 1; save.legacyUnlockedStage = 1
        save.stageBestWave.clear(); save.stageMilestonesClaimed.clear()
        save.workshop.clear(); save.perks.clear(); save.ultimates.clear(); save.research.clear()
        save.activeResearch.clear(); save.enhancement = 0
    }
}
