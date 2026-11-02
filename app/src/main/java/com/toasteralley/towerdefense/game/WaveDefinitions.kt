package com.toasteralley.towerdefense.game

import kotlin.math.pow

/** One scheduled spawn: wait [delay] seconds, then spawn [type]. */
data class SpawnEntry(val type: EnemyType, val delay: Float)

/**
 * Wave composition and difficulty curve. Tweak these numbers to rebalance the
 * game; see README "Customizing towers and waves".
 */
object WaveDefinitions {
    const val TOTAL_WAVES = 20
    const val FIRST_WAVE_COUNTDOWN = 20f
    const val BETWEEN_WAVE_COUNTDOWN = 12f
    const val STARTING_GOLD = 220
    const val STARTING_LIVES = 20

    /** Enemy HP multiplier for a given wave (1-based). */
    fun hpMultiplier(wave: Int): Float = 1.14f.pow(wave - 1)

    /** Gold bonus for clearing a wave. */
    fun clearBonus(wave: Int): Int = 20 + wave * 5

    fun build(wave: Int): List<SpawnEntry> {
        val list = mutableListOf<SpawnEntry>()
        val interval = (0.9f - wave * 0.025f).coerceAtLeast(0.35f)

        val normals = 5 + wave * 2
        repeat(normals) { list += SpawnEntry(EnemyType.NORMAL, interval) }

        if (wave >= 3) {
            val fast = 2 + (wave - 3) * 2
            list += SpawnEntry(EnemyType.FAST, 1.5f)
            repeat(fast - 1) { list += SpawnEntry(EnemyType.FAST, interval * 0.6f) }
        }
        if (wave >= 5) {
            val tanks = 1 + (wave - 5) / 2
            repeat(tanks) { list += SpawnEntry(EnemyType.TANK, interval * 1.8f) }
        }
        if (wave % 5 == 0) {
            val bosses = wave / 10 + 1
            repeat(bosses) { list += SpawnEntry(EnemyType.BOSS, 2.5f) }
        }
        return list
    }
}
