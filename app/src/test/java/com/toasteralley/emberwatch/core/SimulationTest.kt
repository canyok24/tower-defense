package com.toasteralley.emberwatch.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTest {
    private val OLD_SIX = listOf(Upgrade.DAMAGE, Upgrade.ATTACK_SPEED, Upgrade.CRIT_CHANCE, Upgrade.CRIT_FACTOR, Upgrade.HULL_INTEGRITY, Upgrade.BARRIER)

    private fun run(save: SaveData, buy: Boolean, maxSeconds: Int = 3600, god: Boolean = false, seed: Long = 7): GameEngine {
        val e = GameEngine(save, UltimateWeapon.entries.associateWith { save.level(it) }, 1.0, seed = seed, godMode = god)
        var t = 0
        while (!e.gameOver && t < maxSeconds * 60) {
            e.step(); t++
            if (buy && t % 30 == 0) listOf(Upgrade.DAMAGE, Upgrade.ATTACK_SPEED, Upgrade.HULL_INTEGRITY).minByOrNull { e.runUpgradeCost(it) }?.let { e.buyRunUpgrade(it) }
        }
        return e
    }

    @Test fun freshTowerFallsEventuallyAndEarnsCoins() {
        val e = run(SaveData(), buy = true)
        println("fresh tower: wave ${e.wave}, coins ${e.runCoins}, kills ${e.kills}")
        assertTrue(e.gameOver)
        assertTrue(e.wave in 3..40)
        assertTrue(e.runCoins > 20)
    }

    @Test fun upgradedTowerGoesFurther() {
        val fresh = run(SaveData(), buy = true).wave
        val s = SaveData().apply {
            OLD_SIX.forEach { workshop[it.name] = 40 }
            UltimateWeapon.entries.forEach { ultimates[it.name] = 5 }
            research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1
        }
        val e = run(s, buy = true)
        println("upgraded tower: wave ${e.wave} vs fresh $fresh, coins ${e.runCoins}, diamonds ${e.runDiamonds}")
        assertTrue(e.wave > fresh)
    }

    @Test fun endlessWavesWithGodModeNoCrash() {
        val s = SaveData().apply { Upgrade.entries.forEach { workshop[it.name] = minOf(200, it.maxLevel) }; UltimateWeapon.entries.forEach { ultimates[it.name] = 30 } }
        val e = GameEngine(s, UltimateWeapon.entries.associateWith { 30 }, 1.0, startWave = 990, seed = 2, godMode = true)
        repeat(60 * 600) { e.step() }
        println("god mode from 990: wave ${e.wave}, mobs ${e.mobs.size}, diamonds ${e.runDiamonds}")
        assertTrue(e.wave > 990)
        assertTrue(!e.gameOver)
        assertTrue(e.mobs.size <= GameEngine.MAX_MOBS + 2)
        e.debugSkipWaves(10); e.debugSpawnBoss()
        assertTrue(e.bossAlive)
    }
}
