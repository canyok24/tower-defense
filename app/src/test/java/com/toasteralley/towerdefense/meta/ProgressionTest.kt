package com.toasteralley.towerdefense.meta

import com.toasteralley.towerdefense.SettingsStore
import com.toasteralley.towerdefense.billing.EntitlementStore
import com.toasteralley.towerdefense.billing.Entitlements
import com.toasteralley.towerdefense.game.GameEngine
import com.toasteralley.towerdefense.game.RunResult
import com.toasteralley.towerdefense.game.Tower
import com.toasteralley.towerdefense.game.TowerType
import com.toasteralley.towerdefense.game.WaveDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ProgressionTest {

    private fun run(tokens: Int = 0, gold: Int = 0, waves: Int = 0) =
        RunResult(victory = false, wavesCleared = waves, kills = 0, bossKills = 0, goldEarned = gold, tokens = tokens)

    // ------------------------------------------------------------ Tokens

    @Test
    fun tokensFormula() {
        assertEquals(0, Progression.tokensForRun(0, 0, 0, false))
        assertEquals(2 * 7 + 130 / 20 + 5 * 1, Progression.tokensForRun(7, 130, 1, false))
        assertEquals(2 * 20 + 900 / 20 + 5 * 8 + 20, Progression.tokensForRun(20, 900, 8, true))
    }

    @Test
    fun engineAwardsTokensOnceAtEndOfRunWinOrLose() {
        val results = mutableListOf<RunResult>()
        val e = GameEngine(object : GameEngine.Events {
            override fun onRunFinished(result: RunResult) { results += result }
        })
        // Nothing happened yet -> no run to award.
        assertNull(e.finishRun())
        e.startNextWave()
        var t = 0f
        while (e.isRunning && t < 900f) { e.update(GameEngine.STEP); t += GameEngine.STEP }
        assertEquals(GameEngine.State.GAME_OVER, e.state)
        assertEquals(1, results.size)
        val r = results[0]
        assertFalse(r.victory)
        assertEquals(Progression.tokensForRun(r.wavesCleared, r.kills, r.bossKills, false), r.tokens)
        // Idempotent.
        e.finishRun(); e.finishRun()
        assertEquals(1, results.size)
        assertNotNull(e.runResult)
    }

    @Test
    fun tokensPersistAcrossInstances() {
        val store = MapKeyValueStore()
        val p = Progression(store)
        p.recordRun(run(tokens = 37, gold = 1234, waves = 6))
        p.recordRun(run(tokens = 5, gold = 100, waves = 2))
        val reloaded = Progression(store)
        assertEquals(42, reloaded.tokens)
        assertEquals(1334L, reloaded.lifetimeGold)
        assertEquals(6, reloaded.bestWave)
        assertEquals(2, reloaded.runsPlayed)
        assertTrue(reloaded.buy(MetaUpgrade.DAMAGE))
        assertEquals(1, Progression(store).level(MetaUpgrade.DAMAGE))
        assertEquals(42 - MetaUpgrade.DAMAGE.costFrom(0), Progression(store).tokens)
    }

    @Test
    fun metaUpgradesAreCappedAndCostsRise() {
        val p = Progression(MapKeyValueStore())
        p.recordRun(run(tokens = 10_000_000))
        for (u in MetaUpgrade.entries) {
            assertTrue(u.maxLevel in 10..15)
            var prev = 0
            while (!p.isMaxed(u)) {
                val c = p.nextCost(u)
                assertTrue("$u cost rises", c >= prev); prev = c
                assertTrue(p.buy(u))
            }
            assertFalse(p.buy(u))
            assertEquals(u.maxLevel, p.level(u))
        }
        val m = p.runModifiers()
        assertEquals(1.35f, m.damageMult, 1e-5f)
        assertEquals(1.25f, m.fireRateMult, 1e-5f)
        assertTrue(m.rangeMult <= 1.2f + 1e-5f)
        assertTrue(m.effectMult <= 1.3f + 1e-5f)
        assertTrue(m.killGoldMult <= 1.2f + 1e-5f)
        assertEquals(10, m.bonusLives)
        assertEquals(180, m.bonusStartGold)
        assertEquals(1f, m.prestigeMult, 0f)

        // Meta bonuses multiply on top of level stats.
        val tower = Tower(TowerType.ARROW, 0, 0, m)
        assertEquals(TowerType.ARROW.damage * 1.35f, tower.damage, 1e-3f)
        assertEquals(TowerType.ARROW.fireRate * 1.25f, tower.fireRate, 1e-3f)
        val engine = GameEngine(modifiers = m)
        assertEquals(WaveDefinitions.STARTING_GOLD + 180, engine.gold)
        assertEquals(WaveDefinitions.STARTING_LIVES + 10, engine.lives)
    }

    @Test
    fun notEnoughTokensCannotBuy() {
        val p = Progression(MapKeyValueStore())
        assertFalse(p.buy(MetaUpgrade.DAMAGE))
        assertEquals(0, p.level(MetaUpgrade.DAMAGE))
    }

    // ------------------------------------------------------------ Super Tokens

    @Test
    fun superTokensAccrueFromLifetimeGoldAcrossRuns() {
        val k = Progression.SUPER_TOKEN_GOLD_K
        assertEquals(0, Progression.superTokensForGold(0))
        assertEquals(0, Progression.superTokensForGold(k - 1))
        assertEquals(1, Progression.superTokensForGold(k))
        assertEquals(1, Progression.superTokensForGold(4 * k - 1))
        assertEquals(2, Progression.superTokensForGold(4 * k))
        assertEquals(3, Progression.superTokensForGold(9 * k))
        assertEquals(10, Progression.superTokensForGold(100 * k))

        val p = Progression(MapKeyValueStore())
        // Many small runs add up (lifetime gold is cumulative).
        repeat(8) { p.recordRun(run(gold = (k / 2).toInt())) }
        assertEquals(4 * k, p.lifetimeGold)
        assertEquals(2, p.superTokensEarned)
        assertEquals(2, p.superTokensAvailable)
        assertEquals(9 * k, p.goldForNextSuperToken)
        assertEquals(0f, p.superTokenProgress, 1e-6f)
        p.recordRun(run(gold = (k * 5 / 2).toInt()))
        assertEquals(0.5f, p.superTokenProgress, 1e-6f)
    }

    @Test
    fun prestigeWipesProgressKeepsEntitlementsAndRaisesMultiplier() {
        // Worst case: everything in ONE backing store, so a prestige that
        // cleared the store would also wipe purchases/settings.
        val store = MapKeyValueStore()
        val entitlements = EntitlementStore(store)
        entitlements.savePurchased(Entitlements(removeAds = true, premiumTowers = true))
        val settings = SettingsStore(store)
        settings.soundEnabled = false

        val k = Progression.SUPER_TOKEN_GOLD_K
        val p = Progression(store)
        p.recordRun(run(tokens = 5_000, gold = (9 * k).toInt(), waves = 15))
        MetaUpgrade.entries.forEach { p.buy(it); p.buy(it) }
        assertEquals(3, p.superTokensAvailable)
        assertTrue(p.level(MetaUpgrade.DAMAGE) > 0)
        val damageBefore = Tower(TowerType.ARROW, 0, 0, p.runModifiers()).damage

        assertFalse(p.prestige(4)) // more than available
        assertTrue(p.prestige()) // spend all 3

        // Progression wiped to baseline.
        assertEquals(0, p.tokens)
        MetaUpgrade.entries.forEach { assertEquals(0, p.level(it)) }
        assertEquals(0, p.bestWave)
        assertEquals(0, p.runsPlayed)
        assertEquals(0L, p.cycleGold)
        // Entitlements and settings untouched.
        assertEquals(Entitlements(removeAds = true, premiumTowers = true), entitlements.loadPurchased())
        assertFalse(settings.soundEnabled)
        // Multiplier persists and scales with spent tokens.
        assertEquals(3, p.superTokensSpent)
        val expected = 1f + 0.1f * 3.0.pow(0.8).toFloat()
        assertEquals(expected, p.prestigeMultiplier, 1e-5f)
        assertEquals(expected, Progression(store).prestigeMultiplier, 1e-5f)
        assertEquals(expected, p.runModifiers().prestigeMult, 1e-5f)
        val baseDamage = Tower(TowerType.ARROW, 0, 0, p.runModifiers()).damage
        assertEquals(TowerType.ARROW.damage * expected, baseDamage, 1e-3f)
        assertTrue(damageBefore > 0f)
        // No double earning: the 3 spent tokens can't come back...
        assertEquals(0, p.superTokensAvailable)
        p.recordRun(run(gold = (7 * k - 1).toInt()))
        assertEquals(0, p.superTokensAvailable)
        // ...but progress continues toward the 4th (16k lifetime).
        p.recordRun(run(gold = 1))
        assertEquals(1, p.superTokensAvailable)
    }

    @Test
    fun partialPrestigeKeepsTheRest() {
        val p = Progression(MapKeyValueStore())
        p.recordRun(run(gold = (25 * Progression.SUPER_TOKEN_GOLD_K).toInt()))
        assertEquals(5, p.superTokensAvailable)
        assertTrue(p.prestige(2))
        assertEquals(3, p.superTokensAvailable)
        assertTrue(p.prestige(3))
        assertEquals(5, p.superTokensSpent)
        assertEquals(2, p.prestigeCount)
        assertTrue(Progression.prestigeMultiplierFor(5) > Progression.prestigeMultiplierFor(2))
        // Diminishing returns per token.
        val d1 = Progression.prestigeMultiplierFor(1) - Progression.prestigeMultiplierFor(0)
        val d10 = Progression.prestigeMultiplierFor(10) - Progression.prestigeMultiplierFor(9)
        assertTrue(d10 < d1)
    }
}
