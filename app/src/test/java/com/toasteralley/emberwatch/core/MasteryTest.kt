package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryTest {
    private class FakeClock(var t: Long = 1_000_000L) : Clock { override fun now() = t }
    private fun maxedSave() = SaveData().apply { Upgrade.entries.forEach { workshop[it.name] = it.maxLevel }; bounty = 1e300 }

    @Test fun lockedUntilEveryWorkshopUpgradeIsMaxed() {
        val s = maxedSave()
        assertTrue(MasteryLab.isUnlocked(s))
        s.workshop[Upgrade.TRIBUTE.name] = Upgrade.TRIBUTE.maxLevel - 1
        assertFalse(MasteryLab.isUnlocked(s))
        assertEquals("Max all Workshop upgrades: 31/32", MasteryLab.lockedText(s))
        val lab = MasteryLab(s, FakeClock())
        assertTrue(lab.start(Mastery.DAMAGE_MASTERY) is MasteryLab.StartResult.Blocked)
        assertTrue(s.activeMastery.isEmpty())
        assertTrue(MasteryLab.isUnlocked(s, debugUnlocked = true))
        assertEquals("Max all Workshop upgrades: 0/32", MasteryLab.lockedText(SaveData()))
        assertFalse(DebugGate.researchTabUnlocked(false, SaveData().apply { debugResearchTabUnlocked = true }))
    }

    @Test fun categoriesAndNaming() {
        assertEquals(listOf("Damage Mastery", "Attack Speed Mastery", "Crit Chance Mastery", "Crit Factor Mastery", "Super Crit Mastery"),
            Mastery.of(ResearchCategory.ASSAULT).map { it.label })
        assertEquals(listOf("Hull Integrity Mastery", "Core Repair Mastery", "Barrier Mastery", "Plating Mastery"),
            Mastery.of(ResearchCategory.SHIELD).map { it.label })
        assertEquals(listOf("Bounty Gain Mastery", "Tribute Mastery", "Interest Per Wave Mastery", "Enemy Degrade Mastery"),
            Mastery.of(ResearchCategory.LEDGER).map { it.label })
        // distinct from the Research Bay
        val bay = ResearchProject.entries.map { it.label }.toSet()
        assertTrue(Mastery.entries.none { it.label in bay })
        assertTrue(ResearchProject.LIGHT_SPEED_SHOTS in ResearchProject.entries)
    }

    @Test fun fakeClockTimersAndSlots() {
        val s = maxedSave(); val clock = FakeClock(); val lab = MasteryLab(s, clock)
        val b0 = s.bounty
        assertEquals(MasteryLab.StartResult.Ok, lab.start(Mastery.DAMAGE_MASTERY))
        assertEquals(b0 - Mastery.DAMAGE_MASTERY.bountyCost(0), s.bounty, 0.0)       // paid up front
        assertEquals(MasteryLab.StartResult.Ok, lab.start(Mastery.TRIBUTE_MASTERY))
        assertEquals(MasteryLab.StartResult.Blocked("All slots busy"), lab.start(Mastery.PLATING_MASTERY))
        assertEquals(MasteryLab.StartResult.Blocked("In progress"), lab.check(Mastery.DAMAGE_MASTERY))
        clock.t += Mastery.DAMAGE_MASTERY.durationMs(0) - 1
        assertTrue(lab.tick().isEmpty()); assertEquals(0, s.level(Mastery.DAMAGE_MASTERY))
        clock.t += 1
        assertEquals(listOf(Mastery.DAMAGE_MASTERY, Mastery.TRIBUTE_MASTERY), lab.tick())   // same duration
        assertEquals(1, s.level(Mastery.DAMAGE_MASTERY)); assertEquals(1, s.level(Mastery.TRIBUTE_MASTERY))
        assertTrue(s.activeMastery.isEmpty())
        // durations: hours -> days, capped at 14 days
        for (m in Mastery.entries) {
            assertTrue(m.durationMs(0) >= Mastery.HOUR_MS)
            assertTrue(m.durationMs(m.maxLevel - 1) <= Mastery.MAX_DURATION_MS)
            assertTrue(m.durationMs(m.maxLevel - 1) >= 24 * Mastery.HOUR_MS)
        }
        lab.start(Mastery.BARRIER_MASTERY); lab.completeAllNow()
        assertEquals(1, s.level(Mastery.BARRIER_MASTERY))
    }

    @Test fun costsMonotonicAndFinite() {
        for (m in Mastery.entries) {
            for (l in 0 until m.maxLevel) {
                assertTrue(m.name, m.bountyCost(l).isFinite() && m.bountyCost(l + 1) > m.bountyCost(l))
                assertTrue(m.durationMs(l + 1) >= m.durationMs(l))
            }
        }
    }

    @Test fun masteryEffectsAppliedInPipelines() {
        val base = maxedSave()
        val a = TowerStats.resolve(base)
        val s = maxedSave().apply { Mastery.entries.forEach { masteries[it.name] = it.maxLevel } }
        val b = TowerStats.resolve(s)
        // "how much each level adds": base + (v(l) - v(0)) x 2 at max
        val d = Upgrade.DAMAGE; val h = Upgrade.HULL_INTEGRITY
        assertEquals(d.value(0) + (d.value(d.maxLevel) - d.value(0)) * 2.0, b.damage / b.enhancement, b.damage * 1e-9)
        assertEquals(h.value(0) + (h.value(h.maxLevel) - h.value(0)) * 2.0, b.maxHealth / b.enhancement, b.maxHealth * 1e-9)
        assertEquals(a.critChance + 0.10, b.critChance, 1e-9)
        assertTrue(b.critFactor > a.critFactor && b.superCritFactor >= a.superCritFactor)
        assertEquals(a.defenseAbs * 2.0, b.defenseAbs, a.defenseAbs * 1e-9)
        assertEquals(minOf(0.9, a.defense + 0.05), b.defense, 1e-9)
        assertEquals(a.bountyGain * 2.5, b.bountyGain, 1e-9)
        assertEquals(a.tribute * 2.5, b.tribute, 1e-9)
        assertEquals(a.interest + 0.02, b.interest, 1e-9)
        assertEquals(Upgrade.INTEREST_CAP_WAVE_CASH * 2.0, b.interestCapWaveCash, 1e-9)
        assertEquals(a.degradeMaxWaves + 10, b.degradeMaxWaves)
        // ceilings respected
        assertTrue(b.fireRate <= TowerStats.MAX_FIRE_RATE)
        assertTrue(b.effectiveRegen() <= 0.028 * b.maxHealth + 1e-9)
        // Bounty Gain Mastery reaches the engine's Bounty pipeline
        val e = GameEngine(s, UltimateLoadout.NONE, 1.0, seed = 1)
        val mob = e.spawnMob(MobShape.ELLIPSE, 0.5f, 0.5f)
        e.hit(mob, 1e300)
        assertEquals(mob.bounty * b.bountyGain, e.runBounty, mob.bounty * b.bountyGain * 1e-9)
    }
}
