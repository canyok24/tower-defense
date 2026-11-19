package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MobTest {
    @Test fun exactlyTwentyAllowedShapes() {
        assertEquals(20, MobShape.entries.size)
        for (s in MobShape.entries) for (f in MobShape.FORBIDDEN) assertFalse(s.name, s.name.contains(f))
        assertEquals(20, Palette.MOB_COLORS.size)
        assertEquals(5, OutlineStyle.entries.size)
    }

    @Test fun comboCounts() {
        assertTrue(MobLook.baseComboCount() >= 2000)
        assertTrue(MobLook.comboCountWithExtras() >= 5000)
    }

    @Test fun tierRanking() {
        assertEquals(setOf(MobShape.ELLIPSE, MobShape.ROUNDED_RECT, MobShape.PARALLELOGRAM), MobShape.ofTier(MobTier.COMMON).toSet())
        assertTrue(MobShape.ofTier(MobTier.MID).containsAll(listOf(MobShape.PENTAGON, MobShape.HEXAGON, MobShape.TRAPEZOID, MobShape.RING)))
        assertEquals(setOf(MobShape.HEPTAGON, MobShape.OCTAGON, MobShape.CHEVRON, MobShape.ARROWHEAD, MobShape.CROSS), MobShape.ofTier(MobTier.DANGEROUS).toSet())
        assertEquals(setOf(MobShape.NONAGON, MobShape.DECAGON, MobShape.STAR, MobShape.CLOVER, MobShape.HEART), MobShape.ofTier(MobTier.BOSS_LEVEL).toSet())
        assertEquals(listOf(MobShape.YIN_YANG), MobShape.ofTier(MobTier.ENDGAME))
        assertFalse(MobShape.SPIRAL in MobShape.SPAWNABLE)
    }

    @Test fun earlyWavesOnlyCommon_lateWavesMixed() {
        val rng = Random(1)
        repeat(200) { assertEquals(MobTier.COMMON, WaveRules.pickShape(1, rng).tier) }
        val late = (1..3000).map { WaveRules.pickShape(150, rng).tier }.toSet()
        assertTrue(late.containsAll(MobTier.entries))
    }

    @Test fun spiralBossEveryTenWaves() {
        val e = GameEngine(TowerStats.resolve(SaveData()), emptyMap(), 1.0, startWave = 10, seed = 3, godMode = true)
        repeat(60 * 20) { e.step() }
        assertTrue(e.mobs.any { it.isBoss && it.look.shape == MobShape.SPIRAL })
        val boss = e.mobs.first { it.isBoss }
        val normal = e.mobs.first { !it.isBoss }
        assertTrue(boss.maxHp > normal.maxHp * 10)
        assertTrue(boss.speed < normal.speed)
        assertTrue(boss.radius > normal.radius)
        assertEquals(WaveRules.bossReward(10), boss.coins, 1e-9)
    }

    @Test fun spiralBossIsBiggestOfAllMobs() {
        val maxNormal = MobShape.SPAWNABLE.maxOf { MobProfiles.of(it).radius } * SizeTier.entries.maxOf { it.scale }
        val boss = MobProfiles.of(MobShape.SPIRAL)
        assertTrue(boss.radius > maxNormal)
        assertTrue(boss.speed < MobShape.SPAWNABLE.minOf { MobProfiles.of(it).speed })
        assertTrue(boss.hp > MobShape.SPAWNABLE.maxOf { MobProfiles.of(it).hp })
    }

    @Test fun bossRewardFeedsCoinEconomy() {
        val e = GameEngine(TowerStats.resolve(SaveData()), emptyMap(), coinMultiplier = 2.0, startWave = 30, seed = 9)
        val boss = e.spawnBoss()
        val before = e.runCoins
        e.hit(boss, Double.MAX_VALUE)
        assertEquals(WaveRules.bossReward(30) * 2.0, e.runCoins - before, 1e-9)
        assertEquals(e.runCoins, e.takeUnbankedCoins(), 1e-9)  // banked into SaveData.coins by GameActivity
    }
}
