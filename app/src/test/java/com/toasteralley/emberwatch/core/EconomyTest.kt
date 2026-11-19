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
        assertEquals(1.0, BountyMultipliers.packs(emptySet()), 1e-9)
        assertEquals(1.5, BountyMultipliers.packs(setOf("no_ads")), 1e-9)
        assertEquals(360.0, BountyMultipliers.packs(Products.IDS.toSet()), 1e-9)
        assertEquals(1.5 * 10, BountyMultipliers.packs(setOf("no_ads", "pack_tier5")), 1e-9)
    }

    @Test fun totalMultiplierStacksEverything() {
        val s = SaveData()
        val now = 1_000_000L
        assertEquals(360.0, BountyMultipliers.total(s, Products.IDS.toSet(), now), 1e-9)
        BountyMultipliers.addRewardedChunk(s, now)
        s.research[ResearchProject.BOUNTY_BONUS.name] = 5
        s.enhancement = 10
        assertEquals(360.0 * 1.5 * 1.2 * 2.0, BountyMultipliers.total(s, Products.IDS.toSet(), now), 1e-6)
    }

    @Test fun rewardedChunksStackUpToTwoHours() {
        val s = SaveData(); val now = 0L
        BountyMultipliers.addRewardedChunk(s, now)
        assertEquals(20 * 60_000L, s.boostUntilMs)
        repeat(10) { BountyMultipliers.addRewardedChunk(s, now) }
        assertEquals(2 * 3_600_000L, s.boostUntilMs)
        assertFalse(BountyMultipliers.canAddChunk(s, now))
        assertTrue(BountyMultipliers.boostActive(s, now + 3_599_000L * 2))
        assertFalse(BountyMultipliers.boostActive(s, now + 2 * 3_600_000L))
    }

    @Test fun costCurvesAreExponentialAndIncreasing() {
        for (u in Upgrade.entries) {
            for (l in 0 until u.maxLevel) {
                assertTrue("${u.name} bounty L$l", u.bountyCost(l + 1) >= u.bountyCost(l) && u.bountyCost(l).isFinite())
                assertTrue("${u.name} cash L$l", u.cashCost(l + 1) >= u.cashCost(l) && u.cashCost(l).isFinite())
            }
            assertTrue(u.name, u.bountyCost(u.maxLevel).isFinite() && u.cashCost(u.maxLevel).isFinite())
            assertTrue(u.name, u.bountyCost(40) / u.bountyCost(30) > 3.0)
        }
        for (p in DiamondPerk.entries) for (l in 0 until p.maxLevel) assertTrue(p.cost(l + 1) > p.cost(l))
        for (u in UltimateWeapon.entries) assertEquals(20L, u.cost(0))
    }

    @Test fun compactFormat() {
        assertEquals("999", NumberFormat.compact(999.0))
        assertEquals("1.20M", NumberFormat.compact(1_200_000.0))
        assertEquals("3.00M", NumberFormat.compact(3_000_000.0))
        assertEquals("1.00B", NumberFormat.compact(1e9))
        assertEquals("1.00T", NumberFormat.compact(1e12))
        assertEquals("1.00aa", NumberFormat.compact(1e15))
        assertEquals("2.50ab", NumberFormat.compact(2.5e18))
        assertEquals("1.00ba", NumberFormat.compact(1e93))     // 1000^31 -> group 26 after T
        assertEquals("1.00K", NumberFormat.compact(999_999.0 / 1000 * 1.0000001)) // 999.999 rounds up, not "1000"
        assertEquals("1.00M", NumberFormat.compact(999_999.9))
        assertTrue(NumberFormat.compact(1.7e308).isNotEmpty())
        assertEquals("inf", NumberFormat.compact(Double.POSITIVE_INFINITY))
    }
}
