package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltimatesTest {
    private fun rich() = SaveData().apply { stones = 10_000_000 }

    @Test fun nineUltimatesWithThreeStatsAndOneNameConstant() {
        assertEquals(9, Ultimate.entries.size)
        assertEquals("Lighthouse", UltimateNames.LIGHTHOUSE)
        assertEquals(UltimateNames.LIGHTHOUSE, Ultimate.LIGHTHOUSE.label)
        assertEquals("Ultimates", UltimateNames.TAB)
        for (u in Ultimate.entries) assertEquals(u.name, 3, u.stats.size)
        assertFalse(Ultimate.entries.any { it.label.contains("Spotlight", ignoreCase = true) })
    }

    @Test fun unlockCostsSum9705AndEscalateBySlotOrder() {
        val c = Ultimate.UNLOCK_COSTS
        assertEquals(listOf(5L, 50, 150, 300, 600, 1000, 1700, 2900, 3000), c.toList())
        assertEquals(9705L, c.sum())
        for (i in 1 until c.size) assertTrue(c[i] > c[i - 1])
        // price depends on the slot, not the weapon: whichever UW is first costs 5
        for (first in Ultimate.entries) {
            val s = SaveData().apply { stones = 5 }
            assertTrue(Ultimates.unlock(s, first)); assertEquals(0L, s.stones)
        }
        // unlocking all nine in reverse order spends exactly 9705
        val s = SaveData().apply { stones = 9705 }
        for ((i, u) in Ultimate.entries.reversed().withIndex()) {
            assertEquals(c[i], Ultimates.nextUnlockCost(s))
            assertTrue(Ultimates.unlock(s, u))
        }
        assertEquals(0L, s.stones); assertEquals(null, Ultimates.nextUnlockCost(s))
        assertFalse(Ultimates.unlock(s, Ultimate.DEATH_WAVE))   // already unlocked
    }

    @Test fun cannotUnlockWithoutStones() {
        val s = SaveData().apply { stones = 4 }
        assertFalse(Ultimates.unlock(s, Ultimate.GOLDEN_TOWER)); assertEquals(4L, s.stones)
    }

    @Test fun cannotUpgradeBeforeUnlock() {
        val s = rich()
        for (u in Ultimate.entries) for (i in 0 until 3) {
            assertFalse(Ultimates.canUpgrade(s, u, i)); assertFalse(Ultimates.upgrade(s, u, i))
        }
        assertEquals(10_000_000L, s.stones)
        assertTrue(Ultimates.unlock(s, Ultimate.DEATH_WAVE))
        val before = s.stones
        assertTrue(Ultimates.upgrade(s, Ultimate.DEATH_WAVE, 0))
        assertEquals(1, Ultimates.level(s, Ultimate.DEATH_WAVE, 0))
        assertEquals(before - Ultimate.DEATH_WAVE.stat(0).cost(0), s.stones)
        assertFalse(Ultimates.upgrade(s, Ultimate.BLACK_HOLE, 0))
    }

    @Test fun statCostsEscalateAndLevelsCap() {
        val s = rich(); Ultimate.entries.forEach { Ultimates.unlock(s, it) }
        for (u in Ultimate.entries) for (i in 0 until 3) {
            val st = u.stat(i)
            for (l in 0 until st.maxLevel - 1) assertTrue("${u.name}.$i L$l", st.cost(l + 1) > st.cost(l))
            repeat(st.maxLevel) { assertTrue(Ultimates.upgrade(s, u, i)) }
            assertFalse("${u.name}.$i capped", Ultimates.upgrade(s, u, i))
            assertEquals(st.maxLevel, Ultimates.level(s, u, i))
            assertTrue(st.value(st.maxLevel).isFinite())
        }
    }

    @Test fun damageRangesMatchSpec() {
        fun range(u: Ultimate, i: Int) = u.stat(i).value(0) to u.stat(i).value(u.stat(i).maxLevel)
        fun check(u: Ultimate, i: Int, from: Double, to: Double) {
            val (a, b) = range(u, i)
            assertEquals("${u.name}.$i L0", from, a, from * 1e-9); assertEquals("${u.name}.$i cap", to, b, to * 1e-9)
        }
        check(Ultimate.DEATH_WAVE, 0, 3.0, 12_000.0)
        check(Ultimate.CHAIN_LIGHTNING, 0, 4.0, 6_000.0)
        check(Ultimate.SMART_MISSILES, 0, 8.0, 8_000.0)
        check(Ultimate.INNER_LAND_MINES, 0, 8.0, 8_000.0)
        check(Ultimate.POISON_SWAMP, 0, 8.0, 8_000.0)
        check(Ultimate.LIGHTHOUSE, 0, 1.5, 100.0)
        check(Ultimate.LIGHTHOUSE, 1, 30.0, 360.0)
        check(Ultimate.LIGHTHOUSE, 2, 1.0, 4.0)
        for (u in Ultimate.entries) for (i in 0 until 3) {
            val st = u.stat(i)
            for (l in 0 until st.maxLevel) assertTrue("${u.name}.$i monotonic", st.value(l + 1) != st.value(l))
        }
    }

    @Test fun plusLockedUntilAllNineThenAnyOrderEscalating() {
        val s = rich()
        for (u in Ultimate.entries.take(8)) { Ultimates.unlock(s, u); assertFalse(Ultimates.plusAvailable(s)) }
        assertFalse(Ultimates.buyPlus(s, Ultimate.GOLDEN_TOWER))
        Ultimates.unlock(s, Ultimate.entries.last())
        assertTrue(Ultimates.plusAvailable(s))
        val order = listOf(Ultimate.POISON_SWAMP, Ultimate.GOLDEN_TOWER, Ultimate.LIGHTHOUSE, Ultimate.BLACK_HOLE, Ultimate.SMART_MISSILES,
            Ultimate.CHRONO_FIELD, Ultimate.DEATH_WAVE, Ultimate.INNER_LAND_MINES, Ultimate.CHAIN_LIGHTNING)
        var last = 0L
        for ((i, u) in order.withIndex()) {
            val c = Ultimates.nextPlusCost(s)!!
            assertEquals(Ultimate.PLUS_COSTS[i], c); assertTrue(c > last); last = c
            val before = s.stones
            assertTrue(Ultimates.buyPlus(s, u)); assertEquals(before - c, s.stones)
            assertFalse(Ultimates.buyPlus(s, u))
        }
        assertEquals(9, s.uwPlus.size); assertEquals(null, Ultimates.nextPlusCost(s))
    }

    // ------------------------------------------------------------ Lighthouse stacking
    @Test fun lighthouseAdditiveWhenNotMaxed() {
        assertEquals(1.0, Ultimates.lighthouseMultiplier(emptyList(), false), 0.0)
        assertEquals(100.0, Ultimates.lighthouseMultiplier(listOf(100.0), false), 0.0)
        assertEquals(400.0, Ultimates.lighthouseMultiplier(listOf(100.0, 100.0, 100.0, 100.0), false), 0.0)
        assertEquals(3.0, Ultimates.lighthouseMultiplier(listOf(1.5, 1.5), false), 1e-12)
    }

    @Test fun lighthouseMultiplicativeWhenFourBeamsAndAngleMaxed() {
        assertEquals(1e8, Ultimates.lighthouseMultiplier(listOf(100.0, 100.0, 100.0, 100.0), true), 1e-3)
        assertEquals(125.0 * 125 * 125 * 125, Ultimates.lighthouseMultiplier(List(4) { 125.0 }, true), 1e-3)
    }

    @Test fun lighthouseNeverOverflowsOrNaN() {
        for (v in listOf(Ultimates.lighthouseMultiplier(List(4) { 1e200 }, true),
                Ultimates.lighthouseMultiplier(List(4) { Double.MAX_VALUE }, false),
                Ultimates.lighthouseMultiplier(listOf(Double.NaN, 2.0), true),
                Ultimates.lighthouseMultiplier(listOf(Double.POSITIVE_INFINITY, 0.0), true))) {
            assertTrue("$v", v.isFinite() && !v.isNaN() && v >= 1.0)
        }
    }

    private fun engineWithMob(loadout: UltimateLoadout): Pair<GameEngine, Mob> {
        val e = GameEngine(SaveData(), loadout, 1.0, seed = 3, godMode = true)
        var t = 0
        while (e.mobs.none { it.alive } && t < 60 * 30) { e.step(); t++ }
        return e to e.mobs.first { it.alive }
    }

    @Test fun engineLighthouseMaxedIsMultiplicative() {
        val (e, m) = engineWithMob(UltimateLoadout.all(level = 1000))   // all stats capped
        assertEquals(4, e.lighthouseBeams); assertEquals(360.0, e.lighthouseAngleDeg, 1e-9)
        assertTrue(e.lighthouseFullCoverage)
        assertEquals(4, e.beamsCovering(m).size)
        assertEquals(1e8, e.lighthouseMultiplierOn(m), 1e-2)
    }

    @Test fun engineLighthouseNotMaxedIsAdditive() {
        val values = UltimateLoadout.all(level = 1000).values.toMutableMap()
        values[Ultimate.LIGHTHOUSE] = doubleArrayOf(100.0, 359.0, 4.0)   // four beams, angle not maxed
        val (e, m) = engineWithMob(UltimateLoadout(Ultimate.entries.toSet(), values, emptySet()))
        assertFalse(e.lighthouseFullCoverage)
        val n = e.beamsCovering(m).size
        assertTrue(n in 1..4)
        assertEquals(100.0 * n, e.lighthouseMultiplierOn(m), 1e-9)
    }

    @Test fun everyUltimateRunsAtCapWithoutNaN() {
        val e = GameEngine(SaveData(), UltimateLoadout.all(level = 1000, plus = true), 1.0, startWave = 50, seed = 5, godMode = true)
        repeat(60 * 120) { e.step() }
        for (u in Ultimate.entries) e.debugTrigger(u)
        repeat(60 * 30) { e.step() }
        assertTrue(e.mobs.all { !it.hp.isNaN() })
        assertTrue(e.runBounty.isFinite() && !e.runBounty.isNaN())
        assertTrue(e.wave >= 50)
    }

    @Test fun debugTriggerForceUnlocksForTheRun() {
        val (e, _) = engineWithMob(UltimateLoadout.NONE)
        assertFalse(e.trigger(Ultimate.GOLDEN_TOWER))
        assertTrue(e.debugTrigger(Ultimate.GOLDEN_TOWER, forceUnlock = true))
        assertTrue(e.goldenTimer > 0f)
    }

    // ------------------------------------------------------------ Stones: IAP + events
    @Test fun stonePackIdsAndGrantOncePerToken() {
        assertEquals(listOf("stones_small", "stones_medium", "stones_large"), StonePacks.IDS)
        assertEquals(listOf(50L, 300L, 1500L), StonePacks.ALL.map { it.stones })
        assertTrue(StonePacks.IDS.none { it in Products.IDS })
        val s = SaveData()
        assertEquals(300L, StonePacks.grant(s, "tok-1", listOf("stones_medium")))
        assertEquals(0L, StonePacks.grant(s, "tok-1", listOf("stones_medium")))
        assertEquals(300L, s.stones)
        assertEquals(1500L, StonePacks.grant(s, "tok-2", listOf("stones_large")))
        assertEquals(0L, StonePacks.grant(s, "tok-3", listOf("unknown_product")))
        assertEquals(0L, StonePacks.grant(s, "", listOf("stones_small")))
        assertEquals(1800L, s.stones)
    }

    @Test fun eventsAwardOnce() {
        val s = SaveData()
        assertEquals(20L, GameEvents.handle(s, GameEvent.BestWave(50, 250)))   // waves 100 and 200
        assertEquals(0L, GameEvents.handle(s, GameEvent.BestWave(0, 250)))
        assertEquals(10L, GameEvents.handle(s, GameEvent.BestWave(250, 300)))
        assertEquals(15L, GameEvents.handle(s, GameEvent.StageMilestone(3, 0)))
        assertEquals(0L, GameEvents.handle(s, GameEvent.StageMilestone(3, 0)))
        assertEquals(15L, GameEvents.handle(s, GameEvent.StageMilestone(3, 1)))
        assertEquals(200L, GameEvents.handle(s, GameEvent.StageClear(2)))
        assertEquals(0L, GameEvents.handle(s, GameEvent.StageClear(2)))
        for (a in GameAchievement.entries) {
            assertEquals(a.stones, GameEvents.handle(s, GameEvent.Achievement(a)))
            assertEquals(0L, GameEvents.handle(s, GameEvent.Achievement(a)))
        }
        // boss kills are repeatable and scale with stage
        assertEquals(4L, GameEvents.handle(s, GameEvent.BossKill(4)))
        assertEquals(4L, GameEvents.handle(s, GameEvent.BossKill(4)))
    }

    @Test fun awardedEventsSurviveSaveRoundTripKeys() {
        val s = SaveData()
        GameEvents.handle(s, GameEvent.StageClear(1))
        assertTrue("stageclear:1" in s.awardedEvents)
    }

    // ------------------------------------------------------------ migration: old Diamond UWs refunded
    @Test fun migrationRefundsDiamondsForRemovedUltimates() {
        assertEquals(0L, SaveMigrations.oldUwDiamondsSpent(0))
        assertEquals(20L, SaveMigrations.oldUwDiamondsSpent(1))
        assertEquals(54L, SaveMigrations.oldUwDiamondsSpent(3))          // 20 + 14 + 20
        val s = SaveData().apply { version = 5; diamonds = 7 }
        val m = SaveMigrations.migrate(s, 5, LegacyFields(oldUltimates = mapOf("EMBER_NOVA" to 3, "CHAIN_ARC" to 1, "GRAVITY_WELL" to 0, "SOMETHING_ELSE" to 9)))
        assertEquals(7L + 54 + 20, m.diamonds)
        assertEquals(SaveData.CURRENT_VERSION, m.version)
        // a v6 save is not refunded again
        val again = SaveMigrations.migrate(m, SaveData.CURRENT_VERSION, LegacyFields(oldUltimates = mapOf("EMBER_NOVA" to 3)))
        assertEquals(81L, again.diamonds)
    }
}
