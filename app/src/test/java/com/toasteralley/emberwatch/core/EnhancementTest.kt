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
        assertEquals(100, Enhancement.ENHANCEMENT_CAP)
        assertTrue(Enhancement.cost(99) > 1e8) // very expensive at the cap
        for (l in 0 until 100) assertTrue(Enhancement.cost(l + 1) > Enhancement.cost(l))
    }
}
