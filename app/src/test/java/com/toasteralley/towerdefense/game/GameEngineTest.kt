package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class GameEngineTest {

    private fun simulate(engine: GameEngine, seconds: Float, dt: Float = 1f / 60f) {
        var t = 0f
        while (t < seconds && engine.isRunning) { engine.update(dt); t += dt }
    }

    @Test
    fun pathTilesAreNotBuildable() {
        val e = GameEngine()
        assertTrue(e.map.isPath(0, 1))
        assertFalse(e.canBuildOn(0, 1))
        assertTrue(e.canBuildOn(0, 0))
    }

    @Test
    fun placeUpgradeSell() {
        val e = GameEngine()
        val start = e.gold
        assertTrue(e.placeTower(TowerType.ARROW, 0, 0))
        assertEquals(start - TowerType.ARROW.cost, e.gold)
        val t = e.towerAt(0, 0)!!
        val cost = t.upgradeCost
        assertTrue(e.upgradeTower(t))
        assertEquals(2, t.level)
        val before = e.gold
        e.sellTower(t)
        assertEquals(before + ((TowerType.ARROW.cost + cost) * Tower.SELL_REFUND).roundToInt(), e.gold)
        assertTrue(e.towers.isEmpty())
    }

    @Test
    fun premiumTowersLockedUntilUnlocked() {
        val e = GameEngine()
        assertFalse(e.placeTower(TowerType.SNIPER, 0, 0))
        e.premiumUnlocked = true
        assertTrue(e.placeTower(TowerType.SNIPER, 0, 0))
    }

    @Test
    fun undefendedBaseLoses() {
        val e = GameEngine()
        e.startNextWave()
        simulate(e, 600f)
        assertEquals(GameEngine.State.GAME_OVER, e.state)
        assertEquals(0, e.lives)
    }

    @Test
    fun defendedFirstWaveIsCleared() {
        val e = GameEngine()
        e.placeTower(TowerType.ARROW, 2, 2)
        e.placeTower(TowerType.ARROW, 4, 3)
        e.placeTower(TowerType.CANNON, 4, 5)
        e.startNextWave()
        var t = 0f
        while (t < 120f && e.state == GameEngine.State.WAVE_ACTIVE) { e.update(1f / 60f); t += 1f / 60f }
        assertEquals(GameEngine.State.BETWEEN_WAVES, e.state)
        assertEquals(1, e.wave)
        assertEquals(WaveDefinitions.STARTING_LIVES, e.lives)
    }

    @Test
    fun wavesGetHarder() {
        val w1 = WaveDefinitions.build(1)
        val w10 = WaveDefinitions.build(10)
        assertTrue(w10.size > w1.size)
        assertTrue(w10.any { it.type == EnemyType.BOSS })
        assertTrue(WaveDefinitions.hpMultiplier(10) > WaveDefinitions.hpMultiplier(1))
    }
}
