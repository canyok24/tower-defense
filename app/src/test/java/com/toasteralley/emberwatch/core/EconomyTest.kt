package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EconomyTest {
    @Test fun bossRewardScalesWithWave() {
        assertEquals(50.0, WaveRules.bossReward(10), 1e-9)
        assertEquals(250.0, WaveRules.bossReward(50), 1e-9)
        assertEquals(WaveRules.BOSS_BASE_REWARD * 100, WaveRules.bossReward(1000), 1e-9)
        assertTrue(WaveRules.isBossWave(10)); assertTrue(WaveRules.isBossWave(20)); assertFalse(WaveRules.isBossWave(15))
    }

    @Test fun allPacksStackTo360x() {
        assertEquals(1.0, CoinMultipliers.packs(emptySet()), 1e-9)
        assertEquals(1.5, CoinMultipliers.packs(setOf("no_ads")), 1e-9)
        assertEquals(360.0, CoinMultipliers.packs(Products.IDS.toSet()), 1e-9)
        assertEquals(1.5 * 10, CoinMultipliers.packs(setOf("no_ads", "pack_tier5")), 1e-9)
    }

    @Test fun totalMultiplierStacksEverything() {
        val s = SaveData()
        val now = 1_000_000L
        assertEquals(360.0, CoinMultipliers.total(s, Products.IDS.toSet(), now), 1e-9)
        CoinMultipliers.addRewardedChunk(s, now)
        s.research[ResearchProject.COIN_BONUS.name] = 5
        s.enhancement = 10
        assertEquals(360.0 * 1.5 * 1.2 * 2.0, CoinMultipliers.total(s, Products.IDS.toSet(), now), 1e-6)
    }

    @Test fun rewardedChunksStackUpToTwoHours() {
        val s = SaveData(); val now = 0L
        CoinMultipliers.addRewardedChunk(s, now)
        assertEquals(20 * 60_000L, s.boostUntilMs)
        repeat(10) { CoinMultipliers.addRewardedChunk(s, now) }
        assertEquals(2 * 3_600_000L, s.boostUntilMs)
        assertFalse(CoinMultipliers.canAddChunk(s, now))
        assertTrue(CoinMultipliers.boostActive(s, now + 3_599_000L * 2))
        assertFalse(CoinMultipliers.boostActive(s, now + 2 * 3_600_000L))
    }

    @Test fun costCurvesAreExponentialAndIncreasing() {
        for (w in WorkshopStat.entries) {
            for (l in 0 until 100) assertTrue("${w.name} L$l", w.cost(l + 1) >= w.cost(l))
            assertTrue(w.cost(50) / w.cost(40) > 3.0)
        }
        for (p in DiamondPerk.entries) for (l in 0 until p.maxLevel) assertTrue(p.cost(l + 1) > p.cost(l))
        for (u in UltimateWeapon.entries) assertEquals(20L, u.cost(0))
        for (r in RunUpgrade.entries) assertTrue(r.cost(10) > r.cost(0))
    }

    @Test fun milestoneDiamondsPaidOnce() {
        val s = SaveData()
        assertEquals(15L, RunRewards.applyRunEnd(s, 34, 1))
        assertEquals(0L, RunRewards.applyRunEnd(s, 25, 1))
        assertEquals(5L, RunRewards.applyRunEnd(s, 41, 1))
        assertEquals(41, s.bestWave)
        RunRewards.applyRunEnd(s, 50, 1)
        assertEquals(2, s.unlockedTier)
    }

    @Test fun compactFormat() {
        assertEquals("999", NumberFormat.compact(999.0))
        assertEquals("1.20M", NumberFormat.compact(1_200_000.0))
        assertEquals("3.00M", NumberFormat.compact(3_000_000.0))
    }
}
