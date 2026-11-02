package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class UpgradeCostTest {

    @Test
    fun exactCostMultipliers() {
        val expected = mapOf(13 to 3.0, 14 to 3.0, 15 to 1.2, 16 to 1.2, 17 to 1.2, 18 to 3.0, 19 to 1.2, 20 to 1.2)
        for (type in TowerType.entries) {
            assertEquals(type.upgradeBase.toDouble(), UpgradeCosts.rawCostToReach(type, 2), 0.0)
            for (l in 3..20) {
                val ratio = UpgradeCosts.rawCostToReach(type, l) / UpgradeCosts.rawCostToReach(type, l - 1)
                val want = expected[l] ?: 1.13
                assertEquals("$type L$l", want, ratio, 1e-9)
                if (l <= 12) assertTrue(ratio in 1.12..1.15)
                // Charged cost is the raw cost rounded to a nice number.
                val rounded = UpgradeCosts.costToReach(type, l)
                assertEquals(UpgradeCosts.niceRound(UpgradeCosts.rawCostToReach(type, l)), rounded)
                assertTrue(rounded >= UpgradeCosts.costToReach(type, l - 1))
            }
        }
    }

    @Test
    fun niceRounding() {
        assertEquals(35, UpgradeCosts.niceRound(33.9))
        assertEquals(310, UpgradeCosts.niceRound(305.4))
        assertEquals(4750, UpgradeCosts.niceRound(4749.0))
        assertEquals(12300, UpgradeCosts.niceRound(12_345.0))
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
        e.gold = (sum3 + UpgradeCosts.costToReach(TowerType.CANNON, 10) - 1).toLong()
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
        assertEquals((invested * 0.7f).roundToInt().toLong(), e.gold)
    }

    @Test
    fun bulkClampsAtMaxLevel() {
        val t = Tower(TowerType.ARROW, 0, 0)
        while (t.level < 18) t.applyUpgrade(0)
        assertEquals(
            UpgradeCosts.costToReach(TowerType.ARROW, 19) + UpgradeCosts.costToReach(TowerType.ARROW, 20),
            t.bulkCost(5)
        )
    }
}
