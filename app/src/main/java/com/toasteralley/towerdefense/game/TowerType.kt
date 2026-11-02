package com.toasteralley.towerdefense.game

/**
 * Tower definitions. All distances are in tiles, rates in shots/second.
 *
 * To add or rebalance a tower, edit (or add) an entry here. The build panel
 * automatically lists every entry in declaration order.
 */
enum class TowerType(
    val displayName: String,
    val cost: Int,
    val range: Float,
    val damage: Float,
    val fireRate: Float,
    /** Projectile speed in tiles/s. 0 means instant hit (beam). */
    val projectileSpeed: Float,
    val splashRadius: Float = 0f,
    /** Multiplier applied to enemy speed while slowed (1 = no slow). */
    val slowFactor: Float = 1f,
    val slowDuration: Float = 0f,
    /** Premium towers require the "unlock_premium_towers" purchase. */
    val premium: Boolean = false,
    val color: Int,
    val description: String,
) {
    ARROW(
        displayName = "Arrow", cost = 50, range = 2.6f, damage = 10f, fireRate = 1.4f,
        projectileSpeed = 9f, color = 0xFF8BC34A.toInt(),
        description = "Cheap, reliable single-target damage."
    ),
    CANNON(
        displayName = "Cannon", cost = 100, range = 2.3f, damage = 26f, fireRate = 0.6f,
        projectileSpeed = 5f, splashRadius = 1.1f, color = 0xFFFF9800.toInt(),
        description = "Slow shells that deal splash damage."
    ),
    FROST(
        displayName = "Frost", cost = 80, range = 2.2f, damage = 4f, fireRate = 1.0f,
        projectileSpeed = 7f, splashRadius = 0.6f, slowFactor = 0.5f, slowDuration = 1.6f,
        color = 0xFF4FC3F7.toInt(),
        description = "Chills enemies, slowing them by 50%."
    ),
    SNIPER(
        displayName = "Sniper", cost = 150, range = 5.5f, damage = 70f, fireRate = 0.4f,
        projectileSpeed = 22f, premium = true, color = 0xFF9C27B0.toInt(),
        description = "Premium: huge range and heavy hits."
    ),
    LASER(
        displayName = "Laser", cost = 200, range = 3.0f, damage = 7f, fireRate = 6f,
        projectileSpeed = 0f, premium = true, color = 0xFFF44336.toInt(),
        description = "Premium: continuous high-rate beam."
    );
}
