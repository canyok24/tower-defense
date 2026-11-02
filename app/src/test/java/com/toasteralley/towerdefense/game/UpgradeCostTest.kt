package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class UpgradeCostTest {

    @Test
    fun exactCostMultipliersUpToLevel100() {
        val jumps = setOf(13, 14, 18, 28, 38, 48)
        for (type in TowerType.entries) {
            assertEquals(type.upgradeBase.toDouble(), UpgradeCosts.rawCostToReach(type, 2), 0.0)
            for (l in 3..100) {
                val ratio = UpgradeCosts.rawCostToReach(type, l) / UpgradeCosts.rawCostToReach(type, l - 1)
                val want = when {
                    l in jumps -> 3.0
                    l <= 12 -> 1.13
                    else -> 1.2
                }
                assertEquals("$type L$l", want, ratio, 1e-9)
                // Charged cost is the raw cost rounded to a nice number; never decreasing.
                val rounded = UpgradeCosts.costToReach(type, l)
                assertEquals(UpgradeCosts.niceRound(UpgradeCosts.rawCostToReach(type, l)), rounded)
                assertTrue("$type L$l", rounded >= UpgradeCosts.costToReach(type, l - 1))
                // Rounding error stays tiny relative to the price (applied to the unrounded chain).
                val raw = UpgradeCosts.rawCostToReach(type, l)
                assertTrue("$type L$l rounding", kotlin.math.abs(rounded - raw) / raw < 0.1)
            }
        }
        // Exactly x3 at each jump level and x1.2 at every other level 15..100.
        for (l in jumps) assertEquals(3.0, UpgradeCosts.multiplierFor(l), 0.0)
        for (l in 15..100) if (l !in jumps) assertEquals(1.2, UpgradeCosts.multiplierFor(l), 0.0)
        for (l in 3..12) assertEquals(1.13, UpgradeCosts.multiplierFor(l), 0.0)
    }

    @Test
    fun earlyCostsUnchanged() {
        // Levels 2..20 are identical to the 20-level version.
        val arrow = listOf(30L, 35L, 40L, 45L, 50L, 55L, 60L, 70L, 80L, 90L, 100L, 310L, 920L, 1100L, 1300L, 1600L, 4750L, 5700L, 6850L)
        assertEquals(arrow, (2..20).map { UpgradeCosts.costToReach(TowerType.ARROW, it) })
    }

    @Test
    fun niceRounding() {
        assertEquals(35L, UpgradeCosts.niceRound(33.9))
        assertEquals(310L, UpgradeCosts.niceRound(305.4))
        assertEquals(4750L, UpgradeCosts.niceRound(4749.0))
        assertEquals(12300L, UpgradeCosts.niceRound(12_345.0))
        assertEquals(1_235_000L, UpgradeCosts.niceRound(1_234_567.0))
        assertEquals(230_900_000_000L, UpgradeCosts.niceRound(2.30938e11))
    }

    @Test
    fun noOverflowAtLevel100() {
        for (type in TowerType.entries) {
            val total = UpgradeCosts.costRange(type, 1, 100)
            val sumSteps = (2..100).fold(0L) { acc, l -> acc + UpgradeCosts.costToReach(type, l) }
            assertEquals(sumSteps, total)
            assertTrue("$type total $total", total > 0 && total < WaveDefinitions.MAX_GOLD / 1000)
            assertTrue(UpgradeCosts.rawCostToReach(type, 100).isFinite())
            val s = TowerStats.of(type, 100)
            assertTrue(s.damage.isFinite() && s.fireRate.isFinite())
        }
        // Arrow L1 -> L100.
        assertEquals(1_385_498_749_235L, UpgradeCosts.costRange(TowerType.ARROW, 1, 100))
    }

    @Test
    fun bulkBuySumsCostsExactly() {
        val e = GameEngine()
        e.gold = 1_000_000
        assertTrue(e.placeTower(TowerType.CANNON, 0, 0))
        val t = e.towerAt(0, 0)!!
        val sum5 = (2..6).sumOf { UpgradeCosts.costToReach(TowerType.CANNON, it) }
        assertEquals(sum5, t.bulkCost(5))
        val before = e.gold
        assertTrue(e.upgradeTower(t, 5))
        assertEquals(6, t.level)
        assertEquals(before - sum5, e.gold)

        // "Max affordable": exactly enough for levels 7..9 plus change.
        val sum3 = (7..9).sumOf { UpgradeCosts.costToReach(TowerType.CANNON, it) }
        e.gold = sum3 + UpgradeCosts.costToReach(TowerType.CANNON, 10) - 1
        assertEquals(3, t.maxAffordable(e.gold))
        assertEquals(sum3, t.bulkCost(3))
        val g = e.gold
        assertTrue(e.upgradeTowerMax(t))
        assertEquals(9, t.level)
        assertEquals(g - sum3, e.gold)

        // Not enough for x5 -> nothing changes.
        e.gold = 0
        assertFalse(e.upgradeTower(t, 5))
        assertEquals(9, t.level)

        // Sell refunds 70% of everything invested.
        val invested = TowerType.CANNON.cost + (2..9).sumOf { UpgradeCosts.costToReach(TowerType.CANNON, it) }
        assertEquals(invested, t.invested)
        e.sellTower(t)
        assertEquals((invested * 0.7).roundToLong(), e.gold)
    }

    @Test
    fun bulkTenBuysExactlyTenLevels() {
        val e = GameEngine()
        e.gold = WaveDefinitions.MAX_GOLD
        assertTrue(e.placeTower(TowerType.ARROW, 0, 0))
        val t = e.towerAt(0, 0)!!
        assertEquals(10, e.bulkLevels(t, 10))
        val sum10 = (2..11).sumOf { UpgradeCosts.costToReach(TowerType.ARROW, it) }
        assertEquals(sum10, t.bulkCost(10))
        val before = e.gold
        assertTrue(e.upgradeTower(t, 10))
        assertEquals(11, t.level)
        assertEquals(before - sum10, e.gold)
        // Through the expensive jumps: 11 -> 21.
        val sumNext = UpgradeCosts.costRange(TowerType.ARROW, 11, 21)
        assertTrue(e.upgradeTower(t, 10))
        assertEquals(21, t.level)
        assertEquals(before - sum10 - sumNext, e.gold)
    }

    @Test
    fun bulkClampsAtMaxLevel() {
        val e = GameEngine()
        val t = Tower(TowerType.ARROW, 0, 0)
        while (t.level < 95) t.applyUpgrade(0)
        assertEquals(5, e.bulkLevels(t, 10))
        assertEquals(
            (96..100).sumOf { UpgradeCosts.costToReach(TowerType.ARROW, it) },
            t.bulkCost(10)
        )
        assertEquals(t.bulkCost(5), t.bulkCost(10))
        while (t.level < 100) t.applyUpgrade(0)
        assertEquals(0L, t.bulkCost(10))
        assertEquals(0, e.bulkLevels(t, 10))
    }
}
