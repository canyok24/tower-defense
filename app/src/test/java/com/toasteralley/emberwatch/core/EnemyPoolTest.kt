package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EnemyPoolTest {
    private val commonMid = setOf(MobTier.COMMON, MobTier.MID)
    private val withDangerous = commonMid + MobTier.DANGEROUS
    private val all = MobTier.entries.toSet()

    @Test fun poolBoundaries() {
        assertEquals(commonMid, EnemyPool.allowedTiers(0))
        assertEquals(commonMid, EnemyPool.allowedTiers(1))
        assertEquals(commonMid, EnemyPool.allowedTiers(1000))
        assertEquals(withDangerous, EnemyPool.allowedTiers(1001))
        assertEquals(withDangerous, EnemyPool.allowedTiers(3000))
        assertEquals(withDangerous, EnemyPool.allowedTiers(3001))
        assertEquals(withDangerous, EnemyPool.allowedTiers(5000))
        assertEquals(all, EnemyPool.allowedTiers(5001))
        assertEquals(all, EnemyPool.allowedTiers(100_000))
    }

    @Test fun spiralNeverInPool() {
        for (l in listOf(0, 1000, 1001, 5000, 5001, 100_000)) assertFalse(MobShape.SPIRAL in EnemyPool.allowedShapes(l))
        assertTrue(MobShape.YIN_YANG in EnemyPool.allowedShapes(5001))
        assertFalse(MobShape.YIN_YANG in EnemyPool.allowedShapes(5000))
    }

    private fun spawnedTiers(wave: Int, enh: Int): Set<MobTier> {
        val rng = Random(11)
        return (1..5000).map { WaveRules.pickShape(wave, rng, enh).tier }.toSet()
    }

    @Test fun spawnsRespectPoolAndWaveMix() {
        assertEquals(setOf(MobTier.COMMON), spawnedTiers(1, 6000))     // wave still gates the mix
        assertEquals(commonMid, spawnedTiers(500, 0))
        assertEquals(commonMid, spawnedTiers(500, 1000))
        assertEquals(withDangerous, spawnedTiers(500, 1001))
        assertEquals(withDangerous, spawnedTiers(500, 5000))
        assertEquals(all, spawnedTiers(500, 5001))
    }

    @Test fun engineUsesEnhancementPool() {
        val e = GameEngine(SaveData(), UltimateLoadout.NONE, 1.0, startWave = 200, enhancementLevel = 0, seed = 1, godMode = true)
        repeat(60 * 15) { e.step() }
        assertTrue(e.mobs.isNotEmpty())
        assertTrue(e.mobs.filter { !it.isBoss }.all { it.look.shape.tier in commonMid })
    }

    @Test fun lateMidTierMobsGainBehaviours() {
        val base = MobProfiles.of(MobShape.ELLIPSE)
        assertEquals(base, WaveRules.veteran(base, 10))
        val late = WaveRules.veteran(base, 600)
        assertTrue(late.shield >= 1.0)
        assertTrue(late.regen > 0)
        assertEquals(DamageType.PIERCING, late.damageType)
        assertTrue(late.splits)
        assertTrue(late.movement != Movement.STRAIGHT)
        // HP keeps scaling and stays finite for every stage's full-reward wave
        assertTrue(WaveRules.hpScale(1000) > WaveRules.hpScale(500) * 100)
        val maxWave = StageDefinitions.ALL.maxOf { it.fullRewardWave }
        assertTrue((WaveRules.hpScale(maxWave) * StageDefinitions.get(StageDefinitions.STAGE_COUNT).hpMult * 60).isFinite())
        assertTrue((WaveRules.damageScale(maxWave) * StageDefinitions.get(StageDefinitions.STAGE_COUNT).damageMult * 25).isFinite())
    }
}
