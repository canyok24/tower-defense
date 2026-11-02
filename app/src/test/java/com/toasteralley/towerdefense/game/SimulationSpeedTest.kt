package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationSpeedTest {

    private fun setup(): GameEngine = GameEngine().apply {
        premiumUnlocked = true
        gold = 100_000
        placeTower(TowerType.ARROW, 2, 2)
        placeTower(TowerType.CANNON, 4, 3)
        placeTower(TowerType.LASER, 4, 5)
        placeTower(TowerType.DISRUPTOR, 6, 5)
        placeTower(TowerType.SLOW, 8, 3)
        placeTower(TowerType.RAPID, 10, 4)
        placeTower(TowerType.ANNIHILATOR, 12, 6)
        placeTower(TowerType.HEAVY, 8, 7)
        placeTower(TowerType.RAILGUN, 12, 3)
        startNextWave()
    }

    private fun snapshot(e: GameEngine): String = buildString {
        append("g=${e.gold} l=${e.lives} w=${e.wave} k=${e.kills} s=${e.state} c=${e.countdown}\n")
        for (en in e.enemies) append("${en.type} ${en.x} ${en.y} ${en.hp} ${en.stunTimer}\n")
        for (p in e.projectiles) append("p ${p.x} ${p.y}\n")
        for (t in e.towers) append("t ${t.cooldown} ${t.beamTargets.size}\n")
    }

    @Test
    fun nSubstepsAtOneXEqualOneFrameAtNx() {
        for (n in listOf(2, 5, 10, 14)) {
            val a = setup()
            val b = setup()
            b.speedMultiplier = n
            repeat(300) { frame ->
                repeat(n) { a.advance(GameEngine.STEP) }
                val steps = b.advance(GameEngine.STEP)
                assertEquals(n, steps)
                assertEquals("speed ${n}x frame $frame", snapshot(a), snapshot(b))
            }
            assertTrue(a.kills > 0)
        }
    }

    @Test
    fun advanceAlwaysUsesFixedStepsAndCaps() {
        val e = setup()
        e.speedMultiplier = 14
        // A slow frame (50 ms) at 14x would need 42 steps: capped, excess dropped.
        assertEquals(GameEngine.MAX_SUBSTEPS_PER_FRAME, e.advance(0.05f))
        // Next normal frame is not inflated by the dropped time.
        assertEquals(14, e.advance(GameEngine.STEP))
        // Fractions accumulate: two half frames at 1x = one step.
        e.speedMultiplier = 1
        assertEquals(0, e.advance(GameEngine.STEP / 2))
        assertEquals(1, e.advance(GameEngine.STEP / 2))
    }

    @Test
    fun speedOptionsAndCycling() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14), GameEngine.SPEED_OPTIONS)
        val e = GameEngine()
        val seen = mutableListOf<Int>()
        repeat(12) { seen += e.speedMultiplier; e.cycleSpeed() }
        assertEquals(GameEngine.SPEED_OPTIONS, seen)
        assertEquals(1, e.speedMultiplier)
        e.speedMultiplier = 11 // invalid -> 1x
        assertEquals(1, e.speedMultiplier)
        e.speedMultiplier = 10
        assertTrue(e.lowFx)
    }
}
