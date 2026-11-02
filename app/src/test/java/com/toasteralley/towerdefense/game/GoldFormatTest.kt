package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Test

class GoldFormatTest {

    @Test
    fun smallNumbersArePlain() {
        assertEquals("0", GoldFormat.compact(0L))
        assertEquals("50", GoldFormat.compact(50))
        assertEquals("950", GoldFormat.compact(950L))
        assertEquals("999", GoldFormat.compact(999L))
        assertEquals("12.5", GoldFormat.compact(12.5))
        assertEquals("151", GoldFormat.compact(150.72f))
    }

    @Test
    fun unitsKMBT() {
        assertEquals("1K", GoldFormat.compact(1000L))
        assertEquals("1.2K", GoldFormat.compact(1234L))
        assertEquals("34.6K", GoldFormat.compact(34_567L))
        assertEquals("679K", GoldFormat.compact(678_900L))
        assertEquals("1M", GoldFormat.compact(999_960L)) // rounds up into the next unit
        assertEquals("3.4M", GoldFormat.compact(3_400_000L))
        assertEquals("5.6B", GoldFormat.compact(5_600_000_000L))
        assertEquals("7.8T", GoldFormat.compact(7_800_000_000_000L))
        assertEquals("231B", GoldFormat.compact(230_900_000_000L))
        assertEquals("1.4T", GoldFormat.compact(1_385_498_749_235L))
        assertEquals("-1.5K", GoldFormat.compact(-1500L))
    }

    @Test
    fun scientificFrom1e15() {
        assertEquals("1.2e15", GoldFormat.compact(1.2e15))
        assertEquals("1e15", GoldFormat.compact(999.96e12))
        assertEquals("4e18", GoldFormat.compact(WaveDefinitions.MAX_GOLD))
        assertEquals("9.2e18", GoldFormat.compact(Long.MAX_VALUE))
        assertEquals("1e30", GoldFormat.compact(1e30))
        assertEquals("9.9e15", GoldFormat.compact(9.94e15))
        assertEquals("1e16", GoldFormat.compact(9.96e15))
    }

    @Test
    fun nonFinite() {
        assertEquals("0", GoldFormat.compact(Double.NaN))
        assertEquals("∞", GoldFormat.compact(Double.POSITIVE_INFINITY))
    }
}
