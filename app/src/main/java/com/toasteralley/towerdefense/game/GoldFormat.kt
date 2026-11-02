package com.toasteralley.towerdefense.game

import java.util.Locale

/** Compact number formatting for big endless-mode values (12.3K, 4.56M, ...). */
object GoldFormat {
    private val units = arrayOf("", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc")

    fun compact(v: Long): String = compact(v.toDouble())

    fun compact(v: Double): String {
        if (v.isNaN()) return "0"
        val a = kotlin.math.abs(v)
        if (a < 100_000) return String.format(Locale.US, "%d", v.toLong())
        var x = a
        var i = 0
        while (x >= 1000 && i < units.size - 1) { x /= 1000; i++ }
        val s = when {
            x >= 100 -> String.format(Locale.US, "%.0f", x)
            x >= 10 -> String.format(Locale.US, "%.1f", x)
            else -> String.format(Locale.US, "%.2f", x)
        }
        return (if (v < 0) "-" else "") + s + units[i]
    }
}
