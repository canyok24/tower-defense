package com.toasteralley.towerdefense.game

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * Compact number formatting used everywhere gold, costs, HP and damage are shown:
 *  - below 1,000: plain ("950"; non-integers keep one decimal, "12.5")
 *  - 1.2K, 34.5K, 678K, 1.2M, 3.4B, 5.6T (one decimal below 100 of a unit, ".0" dropped)
 *  - from 1e15 on: scientific with one decimal, "1.2e15", "4e18"
 * NaN -> "0", infinities -> "∞".
 */
object GoldFormat {
    private val units = arrayOf("", "K", "M", "B", "T")
    /** Values at or above this switch to scientific notation. */
    const val SCIENTIFIC_FROM = 1e15

    fun compact(v: Long): String = compact(v.toDouble())
    fun compact(v: Int): String = compact(v.toDouble())
    fun compact(v: Float): String = compact(v.toDouble())

    fun compact(v: Double): String {
        if (v.isNaN()) return "0"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        val sign = if (v < 0) "-" else ""
        val a = abs(v)
        if (a < 1000) {
            val r1 = round(a * 10) / 10
            return when {
                r1 >= 1000 -> sign + "1K"
                r1 == floor(r1) || a >= 100 -> sign + String.format(Locale.US, "%d", round(a).toLong())
                else -> sign + String.format(Locale.US, "%.1f", r1)
            }
        }
        if (a >= SCIENTIFIC_FROM) return sign + scientific(a)
        var i = 0
        var x = a
        while (x >= 1000 && i < units.size - 1) { x /= 1000; i++ }
        // Round to display precision, then re-normalise (999.96K -> 1M).
        var r = if (x >= 100) round(x) else round(x * 10) / 10
        if (r >= 1000) {
            if (i == units.size - 1) return sign + scientific(a)
            r /= 1000; i++
        }
        return sign + trim(r) + units[i]
    }

    private fun scientific(a: Double): String {
        var e = floor(log10(a)).toInt()
        var m = round(a / 10.0.pow(e) * 10) / 10
        if (m >= 10) { m /= 10; e++ }
        return trim(m) + "e" + e
    }

    private fun trim(r: Double): String =
        if (r == floor(r)) String.format(Locale.US, "%d", r.toLong()) else String.format(Locale.US, "%.1f", r)
}
