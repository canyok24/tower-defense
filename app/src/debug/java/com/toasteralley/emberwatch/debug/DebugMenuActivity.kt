package com.toasteralley.emberwatch.debug

import com.toasteralley.emberwatch.core.DiamondPerk
import com.toasteralley.emberwatch.core.ResearchBay
import com.toasteralley.emberwatch.core.ResearchProject
import com.toasteralley.emberwatch.core.Products
import com.toasteralley.emberwatch.core.EnemyPool
import com.toasteralley.emberwatch.core.Enhancement
import com.toasteralley.emberwatch.core.StageDefinitions
import com.toasteralley.emberwatch.core.Stages
import com.toasteralley.emberwatch.core.UltimateWeapon
import com.toasteralley.emberwatch.core.Upgrade
import com.toasteralley.emberwatch.core.WorkshopTab
import com.toasteralley.emberwatch.ui.ListScreenActivity

/** DEBUG SOURCE SET ONLY - management override menu. */
class DebugMenuActivity : ListScreenActivity() {
    override val screenTitle = "Management Override"

    override fun buildRows() {
        note("Debug builds only. Release builds do not contain this screen.")
        section("GATES & UNLOCKS")
        addRow("Bypass progression gates", "Opens every menu, research gate and stage", if (save.debugBypassGates) "ON" else "OFF") {
            save.debugBypassGates = !save.debugBypassGates
        }
        addRow("Enhancement level", "${save.enhancement} (cap ${Enhancement.ENHANCEMENT_CAP}; may exceed cap to test enemy pools: " +
            "${EnemyPool.DANGEROUS_UNLOCK_LEVEL}+ dangerous, ${EnemyPool.ALL_UNLOCK_LEVEL}+ all)", "Set") {
            DebugTools.askNumber(this, "Enhancement level (0-100000)") { save.enhancement = it.toInt().coerceIn(0, 100_000); app.persist(); build() }
        }
        addRow("Stage ${save.selectedStage} best cleared wave", "${Stages.best(save, save.selectedStage)} (pays milestones up to it)", "Set") {
            DebugTools.askNumber(this, "Waves cleared on stage ${save.selectedStage}") {
                Stages.recordWave(save, save.selectedStage, it.toInt().coerceAtLeast(0)); app.persist(); build()
            }
        }
        addRow("Unlock all stages", "Grants permanent access to all ${StageDefinitions.STAGE_COUNT} stages (no rewards)", "Unlock") {
            save.legacyUnlockedStage = StageDefinitions.STAGE_COUNT
        }
        addRow("Max all research", "Every Research Bay project to max level", "Max") {
            save.activeResearch.clear(); ResearchProject.entries.forEach { save.research[it.name] = it.maxLevel }
        }
        addRow("Unlock Light Speed Shots only", "Level 1 of the flagship research", "Unlock") {
            save.research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1
        }
        addRow("Unlock all ultimate weapons", "Level 1 each (or keeps higher)", "Unlock") {
            UltimateWeapon.entries.forEach { save.ultimates[it.name] = maxOf(1, save.level(it)) }
        }
        addRow("Set any workshop level", "Pick an ASSAULT / SHIELD / LEDGER upgrade and set its permanent level", "Pick") {
            val ups = Upgrade.entries
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Workshop upgrade")
                .setItems(ups.map { "${it.tab.label} · ${it.label} (Lv ${save.level(it)}/${it.maxLevel})" }.toTypedArray()) { _, i ->
                    val u = ups[i]
                    DebugTools.askNumber(this, "${u.label} level (0-${u.maxLevel})") {
                        save.workshop[u.name] = it.toInt().coerceIn(0, u.maxLevel); app.persist(); build()
                    }
                }.show()
        }
        for (t in WorkshopTab.entries) addRow("Max ${t.label} tab", "All ${Upgrade.ofTab(t).size} upgrades to max level", "Max") {
            Upgrade.ofTab(t).forEach { save.workshop[it.name] = it.maxLevel }
        }
        addRow("Max all workshop tabs", "Every upgrade in all three tabs", "Max") { Upgrade.entries.forEach { save.workshop[it.name] = it.maxLevel } }
        addRow("Reset workshop", "All permanent workshop levels to 0", "Reset") { save.workshop.clear() }
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
