package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Economy curve: income(w) / costToKill(w) and endless-wave safety. */
class EconomyTest {

    @Test
    fun earlyIncomeIsAtLeastTwiceCostToKillForFirst100Waves() {
        for (w in 1..100) {
            val p = WaveDefinitions.plan(w)
            assertTrue("wave $w ratio ${p.ratio}", p.ratio >= 2.0)
        }
        val r1 = WaveDefinitions.plan(1).ratio
        assertTrue("wave 1 ratio $r1", r1 in 4.0..6.0)
    }

    @Test
    fun parityAroundWave1000() {
        val r = WaveDefinitions.plan(1000).ratio
        assertTrue("ratio at 1000 = $r", r in 0.85..1.15)
        for (w in listOf(1500, 2000, 5000, 10_000, 100_000)) {
            assertTrue("w=$w at/below parity", WaveDefinitions.plan(w).ratio <= 1.0)
        }
    }

    @Test
    fun ratioIsMonotoneNonIncreasing() {
        var prev = Double.MAX_VALUE
        for (w in 1..3000) {
            val r = WaveDefinitions.plan(w).ratio
            assertTrue("w=$w r=$r prev=$prev", r <= prev + 1e-9)
            prev = r
        }
        // Designed curve too, sampled far out.
        var pt = Double.MAX_VALUE
        var w = 1
        while (w < 50_000_000) {
            val t = WaveDefinitions.targetRatio(w)
            assertTrue(t <= pt + 1e-12)
            pt = t
            w = w * 3 / 2 + 1
        }
    }

    @Test
    fun incomeSplitsIntoKillAndWaveGold() {
        for (w in listOf(1, 20, 100, 1000)) {
            val p = WaveDefinitions.plan(w)
            assertTrue(p.waveGold > 0 && p.killGold > 0)
            val killShare = p.killGold.toDouble() / p.income
            assertTrue("w=$w killShare=$killShare", killShare in 0.70..0.76)
            assertTrue(p.spawns.all { it.bounty >= 1 })
        }
    }

    @Test
    fun handcraftedWavesKeepTheirComposition() {
        assertEquals(7, WaveDefinitions.plan(1).enemyCount)
        val w20 = WaveDefinitions.plan(20)
        assertEquals(45 + 36 + 8 + 3, w20.enemyCount)
        assertEquals(3, w20.bossCount)
        assertEquals(1.14.pow(19), WaveDefinitions.hpMultiplier(20), 1e-9)
    }

    @Test
    fun proceduralWavesScaleSmoothlyWithBossesEvery5() {
        for (w in 21..300) {
            val p = WaveDefinitions.plan(w)
            if (w % 5 == 0) assertTrue("boss on $w", p.bossCount > 0) else assertEquals(0, p.bossCount)
            assertTrue(p.enemyCount <= WaveDefinitions.MAX_NON_BOSS + WaveDefinitions.MAX_BOSSES + 3)
            assertTrue(p.spawns.all { it.delay >= WaveDefinitions.SPAWN_INTERVAL_FLOOR * 0.6f - 1e-6f })
            // HP multiplier grows smoothly (< 4% per wave after 20).
            val g = WaveDefinitions.hpMultiplier(w) / WaveDefinitions.hpMultiplier(w - 1)
            assertTrue("w=$w growth $g", g > 1.0 && g < 1.04)
        }
        assertTrue(WaveDefinitions.plan(10).bossCount < WaveDefinitions.plan(100).bossCount)
        assertTrue(WaveDefinitions.plan(100).effectiveHp > WaveDefinitions.plan(50).effectiveHp)
        assertTrue(WaveDefinitions.plan(1000).effectiveHp > WaveDefinitions.plan(100).effectiveHp)
        assertTrue(WaveDefinitions.speedMultiplier(1000) > 1f)
        assertTrue(WaveDefinitions.speedMultiplier(Int.MAX_VALUE) <= WaveDefinitions.SPEED_CAP)
    }

    @Test
    fun noOverflowOrNaNAtHugeWaves() {
        for (w in listOf(10_000, 100_000, 1_000_000, Int.MAX_VALUE)) {
            val p = WaveDefinitions.plan(w)
            val nums = listOf(p.effectiveHp, p.duration, p.costToKill, p.ratio, p.enemyHpMultiplier)
            assertTrue("w=$w $nums", nums.all { it.isFinite() && it > 0.0 })
            assertTrue(p.income in 1..WaveDefinitions.MAX_GOLD)
            assertTrue(p.waveGold > 0 && p.killGold > 0)
            assertTrue(p.enemyCount in 1..(WaveDefinitions.MAX_NON_BOSS + WaveDefinitions.MAX_BOSSES + 3))
            assertTrue(p.spawns.all { it.hp.isFinite() && it.hp > 0 && it.hp <= WaveDefinitions.MAX_ENEMY_HP })
            assertTrue(p.spawns.all { it.bounty in 1..WaveDefinitions.MAX_GOLD })
            assertTrue(p.spawns.all { it.speedMult <= WaveDefinitions.SPEED_CAP })
        }
        // At 10,000 the economy is still unclamped and on target.
        val p = WaveDefinitions.plan(10_000)
        assertEquals(WaveDefinitions.targetRatio(10_000), p.ratio, 1e-3)
        // At 100,000 base income is pinned at the MAX_GOLD clamp (Long-safe): finite, below parity.
        val far = WaveDefinitions.plan(100_000)
        assertEquals(WaveDefinitions.MAX_GOLD, far.income)
        assertTrue(far.ratio.isFinite() && far.ratio > 0.0 && far.ratio <= WaveDefinitions.targetRatio(100_000))
    }

    @Test
    fun costToKillFollowsTheDeepUpgradePath() {
        // Continuous and strictly increasing in required DPS, across all three regimes.
        var prev = 0.0
        var dps = 1.0
        while (dps < 1e12) {
            val c = WaveDefinitions.referenceBuildCost(dps)
            assertTrue("dps=$dps c=$c", c.isFinite() && c > prev)
            prev = c
            dps *= 1.07
        }
        val n = WaveDefinitions.REFERENCE_TOWERS
        val d1 = WaveDefinitions.REFERENCE_DPS[1] * n
        assertEquals(n * 50.0, WaveDefinitions.referenceBuildCost(d1), 1e-6)
        // Exactly the cumulative Arrow investment at each whole level.
        for (l in listOf(13, 20, 48, 100)) {
            val d = WaveDefinitions.REFERENCE_DPS[l] * n
            val invest = TowerType.ARROW.cost + UpgradeCosts.costRange(TowerType.ARROW, 1, l)
            assertEquals(n * invest.toDouble(), WaveDefinitions.referenceBuildCost(d), n * invest * 1e-9)
        }
        // The reference build climbs the upgrade path as waves deepen (gold has a sink).
        assertEquals(1, WaveDefinitions.referenceLevelFor(WaveDefinitions.plan(1).let { it.effectiveHp / it.duration }))
        val lvl100 = WaveDefinitions.referenceLevelFor(WaveDefinitions.plan(100).let { it.effectiveHp / it.duration })
        assertTrue("w100 level $lvl100", lvl100 in 25..60)
        val lvl300 = WaveDefinitions.referenceLevelFor(WaveDefinitions.plan(300).let { it.effectiveHp / it.duration })
        assertTrue("w300 level $lvl300", lvl300 in 70..100)
        // Much more expensive than a level-1-only estimate once the tile budget is full.
        val p100 = WaveDefinitions.plan(100)
        assertTrue(p100.costToKill > 50 * p100.effectiveHp / p100.duration / WaveDefinitions.REFERENCE_DPS_PER_GOLD)
    }

    // ------------------------------------------------------------ engine: endless + milestone

    private val dt = GameEngine.STEP

    /** Plays the current wave to completion by deleting enemies as they spawn. */
    private fun clearWave(e: GameEngine) {
        e.startNextWave()
        var guard = 0
        while (e.state == GameEngine.State.WAVE_ACTIVE && guard++ < 200_000) {
            e.update(dt)
            e.enemies.forEach { it.takeDotDamage(Float.MAX_VALUE) }
        }
        assertEquals(GameEngine.State.BETWEEN_WAVES, e.state)
    }

    @Test
    fun wave20IsAMilestoneAndTheRunContinues() {
        var milestones = 0
        val e = GameEngine(object : GameEngine.Events {
            override fun onMilestone(wave: Int) { milestones++ }
        })
        e.debugSetWave(19)
        val before = e.gold
        clearWave(e)
        assertTrue(e.isRunning)
        assertTrue(e.milestoneReached)
        assertEquals(20, e.wavesCleared)
        assertEquals(1, milestones)
        // + early-start bonus (countdown seconds) + wave gold
        assertEquals(before + WaveDefinitions.FIRST_WAVE_COUNTDOWN.toInt() + WaveDefinitions.plan(20).waveGold, e.gold)
        assertTrue(e.bannerText!!.contains("VICTORY"))
        clearWave(e) // wave 21: endless
        clearWave(e)
        assertEquals(22, e.wavesCleared)
        assertEquals(1, milestones)
        val r = e.finishRun()!!
        assertTrue(r.victory)
        assertEquals(
            com.toasteralley.towerdefense.meta.Progression.tokensForRun(22, 0, 0, true), r.tokens
        )
    }

    @Test
    fun engineSurvivesWave10000AndGoldSaturates() {
        val e = GameEngine()
        e.debugSetWave(9_999)
        clearWave(e)
        assertEquals(10_000, e.wave)
        assertTrue(e.gold > 0)
        e.gold = WaveDefinitions.MAX_GOLD - 5
        clearWave(e)
        assertEquals(WaveDefinitions.MAX_GOLD, e.gold)
        assertTrue(e.goldEarned in 1..WaveDefinitions.MAX_GOLD)
        assertFalse(e.towers.any())
    }

    @Test
    fun undefendedEndlessRunEnds() {
        val e = GameEngine()
        e.debugSetWave(500)
        e.startNextWave()
        var t = 0f
        while (e.isRunning && t < 600f) { e.update(dt); t += dt }
        assertEquals(GameEngine.State.GAME_OVER, e.state)
        assertFalse(e.milestoneReached)
    }

    // ------------------------------------------------------------ gold multipliers stack

    @Test
    fun killGoldStacksMetaAndPrestigeMultiplicatively() {
        val mods = RunModifiers(killGoldMult = 1.2f, prestigeKillGoldMult = 1.5f)
        val e = GameEngine(modifiers = mods)
        e.towers += Tower(TowerType.ARROW, 2, 2, mods)
        val enemy = Enemy(EnemyType.NORMAL, 1.0, 3.5f, 2.5f, bounty = 100)
        e.enemies += enemy
        val g = e.gold
        var t = 0f
        while (enemy.alive && t < 3f) { e.update(dt); t += dt }
        assertFalse(enemy.alive)
        assertEquals(g + 180, e.gold)
    }

    @Test
    fun waveGoldUsesPrestigeWaveMultiplier() {
        val e = GameEngine(modifiers = RunModifiers(prestigeWaveGoldMult = 2f))
        val g = e.gold
        clearWave(e)
        assertEquals(g + 2 * WaveDefinitions.plan(1).waveGold, e.gold)
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
