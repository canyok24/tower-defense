package com.toasteralley.emberwatch.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.toasteralley.emberwatch.BuildConfig
import com.toasteralley.emberwatch.ads.AdManager
import com.toasteralley.emberwatch.core.BountyMultipliers
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
import com.toasteralley.emberwatch.core.Ultimate
import com.toasteralley.emberwatch.core.UltimateNames
import com.toasteralley.emberwatch.core.Ultimates
import com.toasteralley.emberwatch.core.Upgrade
import com.toasteralley.emberwatch.core.Workshop
import com.toasteralley.emberwatch.core.WorkshopTab
import kotlinx.coroutines.launch

private fun c(v: Double) = NumberFormat.compact(v)

/**
 * 1. Workshop: four tabs, ASSAULT / SHIELD / LEDGER (Bounty buys permanent levels here; the in-run
 * panel sells temporary levels of the same upgrades for Cash) and ULTIMATES (Stones).
 */
open class WorkshopActivity : ListScreenActivity() {
    override val screenTitle = "Workshop"
    private var tab: WorkshopTab? = WorkshopTab.ASSAULT     // null = ULTIMATES tab
    protected open val startOnUltimates = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        if (startOnUltimates) tab = null
        super.onCreate(savedInstanceState)
    }

    override fun buildRows() {
        list.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val lp = { android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) } }
            for (t in WorkshopTab.entries) addView(emberButton(t.label, t != tab) { tab = t; build() }.apply { textSize = 12f }, lp())
            addView(emberButton(UltimateNames.TAB.uppercase(), tab != null) { tab = null; build() }.apply { textSize = 12f }, lp())
        })
        val t = tab ?: return ultimateRows()
        val s = TowerStats.resolve(save)
        note(when (t) {
            WorkshopTab.ASSAULT -> "DMG ${c(s.damage)} · ${"%.2f".format(s.fireRate)}/s · Crit ${"%.1f".format(s.critChance * 100)}% x${"%.2f".format(s.critFactor)} · Range ${"%.2f".format(s.range)}"
            WorkshopTab.SHIELD -> "Hull ${c(s.maxHealth)} · Repair ${c(s.effectiveRegen())}/s · Barrier ${"%.1f".format(s.defense * 100)}% · Plating -${c(s.defenseAbs)} · Fortification ${c(s.wallMaxHp)}"
            WorkshopTab.LEDGER -> "Bounty x${"%.2f".format(s.bountyGain)} · Cash x${"%.2f".format(s.tribute)} · Interest ${"%.1f".format(s.interest * 100)}%"
        } + "\nPermanent levels (Bounty). In runs, the same upgrades can be raised temporarily with Cash.")
        for (u in Upgrade.ofTab(t)) {
            val lvl = save.level(u)
            if (!Workshop.gateOpen(u, save, app.bypassGates)) { addRow(u.label, "Locked: reach wave ${u.gateWave}", null); continue }
            val maxed = lvl >= u.maxLevel
            val cost = u.bountyCost(lvl)
            addRow("${u.label}  Lv $lvl/${u.maxLevel}", "${u.description}\n${u.format(lvl)} → ${if (maxed) "max" else u.format(lvl + 1)}",
                if (maxed) "MAX" else "${c(cost)} Bounty", Workshop.canBuy(u, save, app.bypassGates)) {
                Workshop.buy(u, save, app.bypassGates)
            }
        }
    }

    /** ULTIMATES tab: nine Stone Ultimate Weapons, three stats each, then Ultimate Weapon Plus. */
    private fun ultimateRows() {
        if (!Gates.open(Gates.ULTIMATES_WAVE, save, app.bypassGates)) { note("Locked: reach wave ${Gates.ULTIMATES_WAVE}."); return }
        val next = Ultimates.nextUnlockCost(save)
        note("Stones ${save.stones} · unlocked ${Ultimates.unlockedCount(save)}/9" +
            (next?.let { " · next unlock costs $it Stones (price set by unlock order)" } ?: " · Ultimate Weapon Plus available") +
            "\nUnlocked weapons fire automatically during runs. Earn Stones from events or buy them in the Store.")
        for (u in Ultimate.entries) {
            section(u.label.uppercase())
            if (!Ultimates.isUnlocked(save, u)) {
                val c = next ?: 0
                addRow("${u.label} (locked)", u.description, "Unlock $c", next != null && save.stones >= c) { Ultimates.unlock(save, u) }
                continue
            }
            for (i in 0 until 3) {
                val st = u.stat(i); val l = Ultimates.level(save, u, i); val maxed = l >= st.maxLevel
                addRow("${st.label}  Lv $l/${st.maxLevel}", "${st.format(l)} → ${if (maxed) "max" else st.format(l + 1)}",
                    if (maxed) "MAX" else "${st.cost(l)} Stones", Ultimates.canUpgrade(save, u, i)) { Ultimates.upgrade(save, u, i) }
            }
        }
        section("ULTIMATE WEAPON PLUS")
        if (!Ultimates.plusAvailable(save)) { note("Unlock all nine Ultimate Weapons to unlock Plus abilities."); return }
        val pc = Ultimates.nextPlusCost(save)
        for (u in Ultimate.entries) {
            val has = Ultimates.hasPlus(save, u)
            addRow(u.plusLabel, u.plusDescription, if (has) "OWNED" else "${pc ?: 0} Stones",
                !has && pc != null && save.stones >= pc) { Ultimates.buyPlus(save, u) }
        }
    }
}

/** 2. Main-menu "Ultimates": the Workshop's ULTIMATES tab (replaces the old Diamond Ultimate Weapons screen). */
class UltimatesActivity : WorkshopActivity() {
    override val startOnUltimates = true
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
        note("Multiplies health, damage, crit bonuses, ultimate damage and all Bounty income. " +
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
            if (maxed) "CAP" else "${c(cost)} Bounty", !maxed && save.bounty >= cost) {
            if (save.bounty >= cost) { save.bounty -= cost; save.enhancement = lvl + 1 }
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
                "\n${c(p.bountyCost(lvl))} Bounty + ${p.coreCost(lvl)} cores · ${NumberFormat.duration(p.durationMs(lvl))}"
            addRow("${p.label}  Lv $lvl/${p.maxLevel}", desc,
                when { maxed -> "DONE"; check is ResearchBay.StartResult.Blocked -> check.reason; else -> "Research" },
                check == ResearchBay.StartResult.Ok) { bay.start(p, bypass) }
        }
    }
}

/**
 * Research tab (Mastery research): separate from the Research Bay. Unlocks once every Workshop
 * upgrade is at max permanent level; costs Bounty; real-time timers; 2 own slots.
 */
class MasteryActivity : ListScreenActivity() {
    override val screenTitle = "Research"
    override val refreshMs = 1000L
    private var cat = com.toasteralley.emberwatch.core.ResearchCategory.ASSAULT
    override fun onTick() { app.tickResearch() }
    override fun buildRows() {
        val lab = app.masteryLab()
        val dbg = app.researchTabDebugUnlocked
        if (!com.toasteralley.emberwatch.core.MasteryLab.isUnlocked(save, dbg)) {
            note("Locked. ${com.toasteralley.emberwatch.core.MasteryLab.lockedText(save)}\n" +
                "Mastery research deepens your maxed upgrades. Research Bay projects are separate.")
            return
        }
        list.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            for (t in com.toasteralley.emberwatch.core.ResearchCategory.entries) addView(emberButton(t.label.removeSuffix(" Research").uppercase(), t != cat) { cat = t; build() },
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        })
        note("${cat.label} · ${com.toasteralley.emberwatch.core.MasteryLab.SLOTS} slots · paid in Bounty up front · timers keep running while the app is closed.")
        if (save.activeMastery.isNotEmpty()) section("IN PROGRESS")
        for (a in save.activeMastery) addRow("${a.mastery.label} → Lv ${a.targetLevel}", "Done in ${NumberFormat.duration(lab.remainingMs(a))}", null)
        section(cat.label.uppercase())
        for (m in com.toasteralley.emberwatch.core.Mastery.of(cat)) {
            val lvl = save.level(m); val maxed = lvl >= m.maxLevel
            val check = lab.check(m, dbg)
            val desc = m.description + "\nNow ${m.format(lvl)}" + if (maxed) " · Complete" else
                " → ${m.format(lvl + 1)} · ${c(m.bountyCost(lvl))} Bounty · ${NumberFormat.duration(m.durationMs(lvl))}"
            addRow("${m.label}  Lv $lvl/${m.maxLevel}", desc,
                when { maxed -> "DONE"; check is com.toasteralley.emberwatch.core.MasteryLab.StartResult.Blocked -> check.reason; else -> "Research" },
                check == com.toasteralley.emberwatch.core.MasteryLab.StartResult.Ok) { lab.start(m, dbg) }
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
        lifecycleScope.launch { app.billing.messages.collect { toast(it); build() } }
        AdManager.preloadRewarded(this)
    }
    override fun buildRows() {
        val owned = app.owned
        val now = System.currentTimeMillis()
        note("All packs are permanent and stack multiplicatively with each other and with research, enhancement and boosts. " +
            "Current pack multiplier: x${"%.1f".format(BountyMultipliers.packs(owned))} · total Bounty multiplier x${c(app.displayBountyMultiplier(now))}")
        for (p in Products.ALL) {
            val has = p.id in owned
            val price = app.billing.priceOf(p.id)
            addRow(p.title, "x${p.bountyMultiplier} Bounty forever" + (if (p.removesAds) " · removes banner & interstitial ads" else "") +
                (if (price == null && !has) "\nUnavailable until published on Google Play" else ""),
                if (has) "Owned" else price ?: p.listPrice, !has) { app.billing.launchPurchase(this, p.id) }
        }
        section("STONES")
        note("Stones unlock and upgrade Ultimate Weapons. Consumable: buy as often as you like. You have ${save.stones}.")
        for (p in com.toasteralley.emberwatch.core.StonePacks.ALL) {
            val price = app.billing.priceOf(p.id)
            addRow(p.title, "${p.stones} Stones" + (if (price == null) "\nUnavailable until published on Google Play" else ""),
                price ?: p.listPrice, true) { app.billing.launchPurchase(this, p.id) }
        }
        section("BOOST")
        val left = (save.boostUntilMs - now).coerceAtLeast(0)
        addRow("Watch an ad: 1.5x Bounty", "Adds 20 minutes (stacks up to 2 hours). " +
            if (left > 0) "Active: ${NumberFormat.duration(left)} left" else "Inactive",
            "Watch", BountyMultipliers.canAddChunk(save, now)) {
            AdManager.showRewarded(this, onReward = {
                BountyMultipliers.addRewardedChunk(save, System.currentTimeMillis()); app.persist(); build()
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
        addRow("Reset progress", "Wipes Bounty, upgrades, research and waves. Purchases are never wiped.", "Reset") {
            AlertDialog.Builder(this).setTitle("Reset all progress?")
                .setMessage("Your store purchases stay owned.")
                .setPositiveButton("Reset") { _, _ -> resetProgress(); app.persist(); build() }
                .setNegativeButton("Cancel", null).show()
        }
        note("Emberwatch ${BuildConfig.VERSION_NAME}")
    }
    private fun resetProgress() {
        val fresh = SaveData()
        save.bounty = fresh.bounty; save.diamonds = 0; save.cores = 0; save.bestWave = 0; save.totalRuns = 0
        save.selectedStage = 1; save.legacyUnlockedStage = 1
        save.stageBestWave.clear(); save.stageMilestonesClaimed.clear()
        save.workshop.clear(); save.perks.clear(); save.research.clear()
        save.masteries.clear(); save.activeMastery.clear()
        // Stones, Ultimates and Stone event awards are kept: Stones can be bought with real money.
        save.activeResearch.clear(); save.enhancement = 0
    }
}
