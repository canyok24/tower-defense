package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementTest {
    @Test fun linearRampToTwoAtLevel10() {
        assertEquals(1.0, Enhancement.multiplier(0), 1e-9)
        for (l in 1..10) assertEquals(1.0 + 0.1 * l, Enhancement.multiplier(l), 1e-9)
        assertEquals(2.0, Enhancement.multiplier(10), 1e-9)
    }

    @Test fun reachesHundredAtLevel1000() {
        assertEquals(100.0, Enhancement.multiplier(1000), 1e-6)
    }

    @Test fun monotonicWithMilestoneJumps() {
        for (l in 0 until 1000) assertTrue("L$l", Enhancement.multiplier(l + 1) > Enhancement.multiplier(l))
        // Milestone levels jump by more than the surrounding steps.
        val step = Enhancement.multiplier(50) / Enhancement.multiplier(49)
        val normal = Enhancement.multiplier(49) / Enhancement.multiplier(48)
        assertTrue(step > normal + 0.04)
    }

    @Test fun capAndCost() {
        assertEquals(1000, Enhancement.ENHANCEMENT_CAP)
        assertEquals(25_000.0 * Math.pow(1.09, 99.0), Enhancement.cost(99), 1.0)
        for (l in 0 until 6000) {
            val c = Enhancement.cost(l)
            assertTrue("L$l finite", c.isFinite())
            assertTrue("L$l increasing", Enhancement.cost(l + 1) > c)
        }
        // steep: every 100 levels past the knee costs ~31x more
        assertEquals(Math.pow(1.035, 100.0), Enhancement.cost(600) / Enhancement.cost(500), 1e-6)
        assertTrue(Enhancement.cost(999) in 1e21..1e22)
        assertTrue(Enhancement.multiplier(Enhancement.ENHANCEMENT_CAP) in 99.99..100.01)
    }
}
