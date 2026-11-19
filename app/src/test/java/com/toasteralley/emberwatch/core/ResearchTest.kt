package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTest {
    private class FakeClock(var t: Long = 1_000L) : Clock { override fun now() = t }

    @Test fun lightSpeedShotsSpec() {
        val p = ResearchProject.LIGHT_SPEED_SHOTS
        assertEquals(1, p.maxLevel)
        assertEquals(3_000_000.0, p.bountyCost(0), 0.0)
        assertEquals(20 * 3_600_000L, p.durationMs(0))
        assertEquals(10, p.gateWave)
    }

    @Test fun timerCompletesOnWallClock() {
        val clock = FakeClock()
        val s = SaveData().apply { bounty = 5_000_000.0; cores = 100; bestWave = 10 }
        val bay = ResearchBay(s, clock)
        assertEquals(ResearchBay.StartResult.Ok, bay.start(ResearchProject.LIGHT_SPEED_SHOTS))
        assertEquals(2_000_000.0, s.bounty, 0.0)
        clock.t += 19 * 3_600_000L
        assertTrue(bay.tick().isEmpty())
        assertEquals(0, s.level(ResearchProject.LIGHT_SPEED_SHOTS))
        clock.t += 3_600_000L
        assertEquals(listOf(ResearchProject.LIGHT_SPEED_SHOTS), bay.tick())
        assertEquals(1, s.level(ResearchProject.LIGHT_SPEED_SHOTS))
        assertTrue(bay.check(ResearchProject.LIGHT_SPEED_SHOTS) is ResearchBay.StartResult.Blocked)
    }

    @Test fun gatesSlotsAndBypass() {
        val clock = FakeClock()
        val s = SaveData().apply { bounty = 1e9; cores = 1000; bestWave = 0 }
        val bay = ResearchBay(s, clock)
        assertTrue(bay.check(ResearchProject.LIGHT_SPEED_SHOTS) is ResearchBay.StartResult.Blocked)
        assertEquals(ResearchBay.StartResult.Ok, bay.check(ResearchProject.LIGHT_SPEED_SHOTS, bypass = true))
        bay.start(ResearchProject.STARTING_CASH)
        s.bestWave = 50
        bay.start(ResearchProject.LAB_DAMAGE)
        assertTrue(bay.check(ResearchProject.LAB_HEALTH) is ResearchBay.StartResult.Blocked) // 2 slots busy
        bay.completeAllNow()
        assertEquals(1, s.level(ResearchProject.STARTING_CASH))
        assertTrue(s.activeResearch.isEmpty())
    }

    @Test fun lightSpeedConvertsVelocityIntoFireRate() {
        val s = SaveData().apply { research[ResearchProject.PROJECTILE_VELOCITY.name] = 10 }
        val before = TowerStats.resolve(s)
        assertFalse(before.lightSpeed)
        s.research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1
        val after = TowerStats.resolve(s)
        assertTrue(after.lightSpeed)
        assertTrue(after.projectileSpeed.isInfinite())
        assertEquals(before.fireRate * 1.3, after.fireRate, 1e-9)
    }

    @Test fun lightSpeedHitsInstantly() {
        val s = SaveData().apply { research[ResearchProject.LIGHT_SPEED_SHOTS.name] = 1 }
        val e = GameEngine(s, UltimateLoadout.NONE, 1.0, seed = 5)
        val m = e.spawnMob(MobShape.ELLIPSE, 0.5f, e.towerY - 0.6f)   // inside the 1.2 base range
        val hp = m.hp
        e.step()
        assertTrue(e.shots.isEmpty())
        assertTrue(m.hp < hp || !m.alive)
    }
}
