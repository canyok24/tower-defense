package com.toasteralley.towerdefense.game

/**
 * Enemy definitions. Speed is tiles/second; HP is scaled per wave by
 * [WaveDefinitions.hpMultiplier].
 */
enum class EnemyType(
    val displayName: String,
    val baseHp: Float,
    val speed: Float,
    val reward: Int,
    /** Lives lost if this enemy reaches the exit. */
    val damageToBase: Int,
    /** Render radius in tiles. */
    val radius: Float,
    val color: Int,
    /** Flat damage reduction per hit (min 1 damage always applies). */
    val armor: Float = 0f,
) {
    NORMAL("Grunt", 40f, 1.25f, 5, 1, 0.26f, 0xFFE53935.toInt()),
    FAST("Runner", 24f, 2.5f, 6, 1, 0.20f, 0xFFFFEB3B.toInt()),
    TANK("Tank", 170f, 0.7f, 14, 2, 0.34f, 0xFF607D8B.toInt(), armor = 3f),
    BOSS("Boss", 1400f, 0.5f, 120, 10, 0.45f, 0xFF7B1FA2.toInt(), armor = 6f);
}
