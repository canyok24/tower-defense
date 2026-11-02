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

    private fun run(tokens: Int = 0, gold: Long = 0, waves: Int = 0) =
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
        assertEquals(WaveDefinitions.STARTING_GOLD + 180L, engine.gold)
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
        assertEquals(1, Progression.superTokensForGold(16 * k - 1))
        assertEquals(2, Progression.superTokensForGold(16 * k))
        assertEquals(3, Progression.superTokensForGold(81 * k))
        assertEquals(10, Progression.superTokensForGold(10_000 * k))
        assertEquals(10, Progression.superTokensForGold(14_641 * k - 1))
        assertEquals(11, Progression.superTokensForGold(14_641 * k))
        // Fourth root: saturated lifetime gold gives a finite, sane count.
        val maxTokens = Progression.superTokensForGold(Long.MAX_VALUE / 2)
        assertTrue(maxTokens in 2000..3000)
        assertTrue(Progression.goldForSuperTokens(maxTokens) <= Long.MAX_VALUE / 2)
        assertTrue(Progression.goldForSuperTokens(maxTokens + 1) > Long.MAX_VALUE / 2)
        assertEquals(Long.MAX_VALUE, Progression.goldForSuperTokens(1_000_000))

        val p = Progression(MapKeyValueStore())
        // Many small runs add up (lifetime gold is cumulative).
        repeat(8) { p.recordRun(run(gold = 2 * k)) }
        assertEquals(16 * k, p.lifetimeGold)
        assertEquals(2, p.superTokensEarned)
        assertEquals(2, p.superTokensAvailable)
        assertEquals(81 * k, p.goldForNextSuperToken)
        assertEquals(0f, p.superTokenProgress, 1e-6f)
        p.recordRun(run(gold = k * 65 / 2))
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
        p.recordRun(run(tokens = 5_000, gold = 81 * k, waves = 15))
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
        p.recordRun(run(gold = 175 * k - 1))
        assertEquals(0, p.superTokensAvailable)
        // ...but progress continues toward the 4th (256k lifetime).
        p.recordRun(run(gold = 1))
        assertEquals(1, p.superTokensAvailable)
    }

    @Test
    fun partialPrestigeKeepsTheRest() {
        val p = Progression(MapKeyValueStore())
        p.recordRun(run(gold = 625 * Progression.SUPER_TOKEN_GOLD_K))
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

    // ------------------------------------------------------------ prestige allocation

    @Test
    fun prestigeAllocatesAcrossThreeCategoriesTrackedSeparately() {
        val store = MapKeyValueStore()
        val entitlements = EntitlementStore(store)
        entitlements.savePurchased(Entitlements(removeAds = true, premiumTowers = false))
        val k = Progression.SUPER_TOKEN_GOLD_K
        val p = Progression(store)
        p.recordRun(run(tokens = 500, gold = 10_000 * k)) // 10 Super Tokens
        assertEquals(10, p.superTokensAvailable)

        assertFalse(p.prestige(PrestigeAllocation(5, 4, 2)))   // 11 > 10
        assertFalse(p.prestige(PrestigeAllocation(0, 0, 0)))
        assertFalse(p.prestige(PrestigeAllocation(-1, 5, 0)))
        assertEquals(500, p.tokens) // nothing changed

        assertTrue(p.prestige(PrestigeAllocation(damage = 4, killGold = 3, waveGold = 2)))
        assertEquals(4, p.spent(PrestigeUpgrade.DAMAGE))
        assertEquals(3, p.spent(PrestigeUpgrade.KILL_GOLD))
        assertEquals(2, p.spent(PrestigeUpgrade.WAVE_GOLD))
        assertEquals(9, p.superTokensSpent)
        assertEquals(1, p.superTokensAvailable)
        assertEquals(0, p.tokens)
        assertEquals(Entitlements(removeAds = true, premiumTowers = false), entitlements.loadPurchased())

        assertEquals(1f + 0.1f * 4.0.pow(0.8).toFloat(), p.prestigeMultiplier, 1e-5f)
        assertEquals(1f + 0.05f * 3.0.pow(0.7).toFloat(), p.prestigeKillGoldMultiplier, 1e-5f)
        assertEquals(1f + 0.05f * 2.0.pow(0.7).toFloat(), p.prestigeWaveGoldMultiplier, 1e-5f)
        val m = Progression(store).runModifiers()
        assertEquals(p.prestigeMultiplier, m.prestigeMult, 1e-6f)
        assertEquals(p.prestigeKillGoldMultiplier, m.prestigeKillGoldMult, 1e-6f)
        assertEquals(p.prestigeWaveGoldMultiplier, m.prestigeWaveGoldMult, 1e-6f)

        // A second prestige adds to the existing per-category totals.
        assertTrue(p.prestige(PrestigeAllocation(waveGold = 1)))
        assertEquals(3, Progression(store).spent(PrestigeUpgrade.WAVE_GOLD))
        assertEquals(4, Progression(store).spent(PrestigeUpgrade.DAMAGE))
    }

    @Test
    fun goldPrestigeGrowsSlowerThanDamageAndIsCapped() {
        for (n in 1..500) {
            val dmg = PrestigeUpgrade.DAMAGE.multiplier(n)
            val kill = PrestigeUpgrade.KILL_GOLD.multiplier(n)
            val wave = PrestigeUpgrade.WAVE_GOLD.multiplier(n)
            assertTrue(kill < dmg && wave < dmg)
            assertTrue(kill <= Progression.KILL_GOLD_CAP && wave <= Progression.WAVE_GOLD_CAP)
            assertTrue(kill >= PrestigeUpgrade.KILL_GOLD.multiplier(n - 1))
        }
        assertEquals(2.5f, PrestigeUpgrade.KILL_GOLD.multiplier(1_000_000), 0f)
        assertEquals(2.0f, PrestigeUpgrade.WAVE_GOLD.multiplier(1_000_000), 0f)
        assertTrue(PrestigeUpgrade.DAMAGE.multiplier(1_000_000) > 100f) // damage uncapped
        // Diminishing returns.
        val d1 = PrestigeUpgrade.KILL_GOLD.multiplier(1) - PrestigeUpgrade.KILL_GOLD.multiplier(0)
        val d20 = PrestigeUpgrade.KILL_GOLD.multiplier(20) - PrestigeUpgrade.KILL_GOLD.multiplier(19)
        assertTrue(d20 < d1)
    }

    @Test
    fun legacySpentMigratesIntoDamage() {
        val store = MapKeyValueStore()
        store.putInt("super_tokens_spent", 4)
        store.putLong("lifetime_gold", 625 * Progression.SUPER_TOKEN_GOLD_K)
        val p = Progression(store)
        assertEquals(4, p.spent(PrestigeUpgrade.DAMAGE))
        assertEquals(0, p.spent(PrestigeUpgrade.KILL_GOLD))
        assertEquals(0, p.spent(PrestigeUpgrade.WAVE_GOLD))
        assertEquals(1, p.superTokensAvailable)
        assertEquals(Progression.prestigeMultiplierFor(4), p.prestigeMultiplier, 0f)
        // Migration runs once: later spending isn't overwritten by the legacy value.
        assertTrue(p.prestige(PrestigeAllocation(damage = 1)))
        assertEquals(5, Progression(store).spent(PrestigeUpgrade.DAMAGE))
    }

    // ------------------------------------------------------------ endless Tokens / best wave

    @Test
    fun tokensAreSublinearForEndlessRuns() {
        // A plain 20-wave victory pays exactly what it used to.
        assertEquals(147, Progression.tokensForRun(20, 942, 8, true))
        var prev = -1
        for (w in listOf(0, 10, 20, 50, 100, 500, 1000, 10_000)) {
            val t = Progression.tokensForRun(w, w * 140, w / 5 * 6, w >= 20)
            assertTrue(t > prev); prev = t
        }
        for (w in listOf(100, 1000, 10_000)) {
            assertTrue(Progression.tokensForRun(2 * w, 0, 0, true) < 2 * Progression.tokensForRun(w, 0, 0, true))
        }
        val huge = Progression.tokensForRun(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, true)
        assertTrue(huge in 1..5000)
    }

    @Test
    fun bestWaveIsUnboundedAndLifetimeGoldSaturates() {
        val store = MapKeyValueStore()
        val p = Progression(store)
        p.recordRun(run(waves = 12_345))
        assertEquals(12_345, p.bestWave)
        p.recordRun(run(gold = Long.MAX_VALUE / 2))
        p.recordRun(run(gold = Long.MAX_VALUE / 2))
        assertTrue(p.lifetimeGold > 0)
        assertTrue(p.superTokensEarned > 0)
        assertTrue(p.superTokenProgress in 0f..1f)
    }
}
