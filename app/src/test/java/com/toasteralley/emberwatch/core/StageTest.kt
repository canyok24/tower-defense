package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    @Test fun tableShape() {
        assertEquals(24, StageDefinitions.ALL.size)
        val s1 = StageDefinitions.get(1); val s2 = StageDefinitions.get(2)
        assertEquals(10_000, s1.fullRewardWave)
        assertEquals(500, s2.unlockWave)
        assertEquals(10_000, s1.milestones.last().wave)
        for (i in 1 until 24) {
            val a = StageDefinitions.ALL[i - 1]; val b = StageDefinitions.ALL[i]
            assertTrue(b.unlockWave >= a.unlockWave)
            assertTrue(b.fullRewardWave > a.fullRewardWave)
            assertTrue(b.hpMult > a.hpMult && b.damageMult > a.damageMult && b.speedMult > a.speedMult && b.coinMult > a.coinMult)
            assertTrue("unlock within previous stage's range", b.unlockWave <= a.fullRewardWave)
            assertEquals(b.fullRewardWave, b.milestones.last().wave)
            assertTrue(b.milestones.zipWithNext().all { (x, y) -> y.wave > x.wave })
        }
    }

    @Test fun stage2UnlocksAfterClearing500InStage1() {
        val s = SaveData()
        assertTrue(Stages.isUnlocked(s, 1)); assertFalse(Stages.isUnlocked(s, 2))
        RunRewards.applyRunEnd(s, waveReached = 500, stage = 1)  // died on 500 -> cleared 499
        assertFalse(Stages.isUnlocked(s, 2))
        RunRewards.applyRunEnd(s, waveReached = 501, stage = 1)  // cleared 500
        assertTrue(Stages.isUnlocked(s, 2)); assertFalse(Stages.isUnlocked(s, 3))
        assertEquals(2, Stages.highestUnlocked(s))
        assertTrue(Stages.isUnlocked(s, 24, bypass = true))
        assertEquals(24, Stages.highestUnlocked(s, bypass = true))
    }

    @Test fun stage1RewardsCompleteAt10000() {
        val s = SaveData()
        val r1 = Stages.recordWave(s, 1, 9_999)
        assertFalse(Stages.isComplete(s, 1))
        assertEquals(10_000, Stages.nextMilestone(s, 1)!!.wave)
        val r2 = Stages.recordWave(s, 1, 10_000)
        assertTrue(Stages.isComplete(s, 1))
        val all = StageDefinitions.get(1).milestones
        assertEquals(all.sumOf { it.reward.diamonds }, r1.diamonds + r2.diamonds)
        assertEquals(all.sumOf { it.reward.diamonds }, s.diamonds)
        assertEquals(all.sumOf { it.reward.cores }, s.cores)
        // replaying pays nothing
        assertEquals(0L, Stages.recordWave(s, 1, 10_000).diamonds)
        assertEquals(all.fold(1.0) { m, x -> m * (1 + x.reward.coinBonus) }, Stages.coinBonus(s), 1e-12)
    }

    @Test fun milestonesPaidAlongTheWay() {
        val s = SaveData()
        assertEquals(10L, Stages.recordWave(s, 1, 10).diamonds)
        assertEquals(0L, Stages.recordWave(s, 1, 24).diamonds)
        assertEquals(15L + 25L, Stages.recordWave(s, 1, 60).diamonds)
        assertEquals(3, Stages.claimed(s, 1))
        assertEquals(60, Stages.best(s, 1))
        assertEquals(0, Stages.best(s, 2))
    }

    @Test fun stageMultipliersApplyToEngineAndCoins() {
        val stats = TowerStats.resolve(SaveData())
        val e1 = GameEngine(stats, emptyMap(), 1.0, stage = 1, seed = 4)
        val e5 = GameEngine(stats, emptyMap(), 1.0, stage = 5, seed = 4)
        val m1 = e1.spawnMob(MobShape.ELLIPSE); val m5 = e5.spawnMob(MobShape.ELLIPSE)
        assertEquals(m1.maxHp * StageDefinitions.get(5).hpMult, m5.maxHp, 1e-6)
        assertTrue(m5.speed > m1.speed)
        val s = SaveData().apply { selectedStage = 5 }
        assertEquals(StageDefinitions.get(5).coinMult, CoinMultipliers.total(s, emptySet(), 0), 1e-9)
    }
}
