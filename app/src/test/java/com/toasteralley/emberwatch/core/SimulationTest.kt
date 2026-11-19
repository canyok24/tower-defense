package com.toasteralley.emberwatch.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTest {
    private val OLD_SIX = listOf(Upgrade.DAMAGE, Upgrade.ATTACK_SPEED, Upgrade.CRIT_CHANCE, Upgrade.CRIT_FACTOR, Upgrade.HULL_INTEGRITY, Upgrade.BARRIER)

    private fun run(save: SaveData, buy: Boolean, maxSeconds: Int = 3600, god: Boolean = false, seed: Long = 7): GameEngine {
        val e = GameEngine(save, UltimateLoadout.from(save), 1.0, seed = seed, godMode = god)
        var t = 0
        while (!e.gameOver && t < maxSeconds * 60) {
            e.step(); t++
            if (buy && t % 30 == 0) listOf(Upgrade.DAMAGE, Upgrade.ATTACK_SPEED, Upgrade.HULL_INTEGRITY).minByOrNull { e.runUpgradeCost(it) }?.let { e.buyRunUpgrade(it) }
        }
        return e
    }

    @Test fun freshTowerFallsEventuallyAndEarnsBounty() {
        val e = run(SaveData(), buy = true)
        println("fresh tower: wave ${e.wave}, bounty ${e.runBounty}, kills ${e.kills}")
        assertTrue(e.gameOver)
        assertTrue(e.wave in 3..40)
        assertTrue(e.runBounty > 20)
    }

    @Test fun upgradedTowerGoesFurther() {
        val fresh = run(SaveData(), buy = true).wave
        val s = SaveData().apply {
            OLD_SIX.forEach { workshop[it.name] = 40 }
            uwUnlockOrder += listOf(Ultimate.DEATH_WAVE.name, Ultimate.CHAIN_LIGHTNING.name)
            for (i in 0 until 3) { uwStats["DEATH_WAVE.$i"] = 3; uwStats["CHAIN_LIGHTNING.$i"] = 3 }
            research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1
        }
        val e = run(s, buy = true)
        println("upgraded tower: wave ${e.wave} vs fresh $fresh, bounty ${e.runBounty}, diamonds ${e.runDiamonds}")
        assertTrue(e.wave > fresh)
    }

    @Test fun endlessWavesWithGodModeNoCrash() {
        val s = SaveData().apply { Upgrade.entries.forEach { workshop[it.name] = minOf(200, it.maxLevel) } }
        val e = GameEngine(s, UltimateLoadout.all(level = 30, plus = true), 1.0, startWave = 990, seed = 2, godMode = true)
        repeat(60 * 600) { e.step() }
        println("god mode from 990: wave ${e.wave}, mobs ${e.mobs.size}, diamonds ${e.runDiamonds}")
        assertTrue(e.wave > 990)
        assertTrue(!e.gameOver)
        assertTrue(e.mobs.size <= GameEngine.MAX_MOBS + 2)
        e.debugSkipWaves(10); e.debugSpawnBoss()
        assertTrue(e.bossAlive)
    }
}
