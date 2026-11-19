package com.toasteralley.emberwatch.debug

import com.toasteralley.emberwatch.core.DiamondPerk
import com.toasteralley.emberwatch.core.ResearchBay
import com.toasteralley.emberwatch.core.ResearchProject
import com.toasteralley.emberwatch.core.Products
import com.toasteralley.emberwatch.core.Tiers
import com.toasteralley.emberwatch.core.UltimateWeapon
import com.toasteralley.emberwatch.ui.ListScreenActivity

/** DEBUG SOURCE SET ONLY - management override menu. */
class DebugMenuActivity : ListScreenActivity() {
    override val screenTitle = "Management Override"

    override fun buildRows() {
        note("Debug builds only. Release builds do not contain this screen.")
        section("GATES & UNLOCKS")
        addRow("Bypass progression gates", "Opens every menu, research gate and tier", if (save.debugBypassGates) "ON" else "OFF") {
            save.debugBypassGates = !save.debugBypassGates
        }
        addRow("Unlock all tiers", "Sets unlocked tier to ${Tiers.MAX_TIER}", "Unlock") { save.unlockedTier = Tiers.MAX_TIER }
        addRow("Max all research", "Every Research Bay project to max level", "Max") {
            save.activeResearch.clear(); ResearchProject.entries.forEach { save.research[it.name] = it.maxLevel }
        }
        addRow("Unlock Light Speed Shots only", "Level 1 of the flagship research", "Unlock") {
            save.research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1
        }
        addRow("Unlock all ultimate weapons", "Level 1 each (or keeps higher)", "Unlock") {
            UltimateWeapon.entries.forEach { save.ultimates[it.name] = maxOf(1, save.level(it)) }
        }
        addRow("Max all diamond perks", "", "Max") { DiamondPerk.entries.forEach { save.perks[it.name] = it.maxLevel } }
        addRow("Complete active research", "${save.activeResearch.size} running", "Complete") { ResearchBay(save).completeAllNow() }

        section("BALANCES")
        addRow("Coins", "%.0f".format(save.coins), "Set") { DebugTools.askNumber(this, "Coins") { save.coins = it; app.persist(); build() } }
        addRow("Diamonds", "${save.diamonds}", "Set") { DebugTools.askNumber(this, "Diamonds") { save.diamonds = it.toLong(); app.persist(); build() } }
        addRow("Research Cores", "${save.cores}", "Set") { DebugTools.askNumber(this, "Cores") { save.cores = it.toLong(); app.persist(); build() } }
        addRow("Best wave", "${save.bestWave}", "Set") { DebugTools.askNumber(this, "Best wave") { save.bestWave = it.toInt(); app.persist(); build() } }

        section("RUNS")
        addRow("Start next run at wave", "${save.debugStartWave}", "Set") {
            DebugTools.askNumber(this, "Start wave") { save.debugStartWave = it.toInt().coerceAtLeast(1); app.persist(); build() }
        }
        addRow("God mode", "Tower takes no damage", if (save.debugGodMode) "ON" else "OFF") { save.debugGodMode = !save.debugGodMode }
        note("In a run: hold the pause button 2 s, then tap the WAVE label 5x within 3 s for skip waves / spawn boss.")

        section("STORE (local grants, not real purchases)")
        for (p in Products.ALL) {
            val has = p.id in app.owned
            addRow(p.title, if (app.billing.isPurchased(p.id)) "Purchased on Play" else "Debug grant", if (has) "Revoke" else "Grant") {
                app.billing.debugGrant(p.id, !has)
            }
        }
    }
}
