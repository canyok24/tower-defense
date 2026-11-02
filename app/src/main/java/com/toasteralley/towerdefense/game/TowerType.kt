package com.toasteralley.towerdefense.game

/** How a tower delivers its damage / effect. */
enum class Attack {
    /** Fires a travelling projectile (optionally splash / slow / boss bonus). */
    PROJECTILE,
    /** Instant hit with a short-lived beam visual. */
    INSTANT,
    /** Continuous damage-over-time beam that branches to extra targets. */
    BEAM,
    /** Deals no damage; periodically stuns every enemy in range. */
    PULSE,
}

/**
 * Tower definitions (level-1 base stats). All distances are in tiles, rates in
 * shots/second. For [Attack.BEAM] towers [damage] is damage per second to the
 * main target. Per-level growth lives in [TowerStats] / [UpgradeCosts].
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
    /** Projectile speed in tiles/s (only for [Attack.PROJECTILE]). */
    val projectileSpeed: Float,
    val attack: Attack = Attack.PROJECTILE,
    val splashRadius: Float = 0f,
    /** Multiplier applied to enemy speed while slowed (1 = no slow). */
    val slowFactor: Float = 1f,
    val slowDuration: Float = 0f,
    /** Disruptor: stun length (s) and seconds between pulses. */
    val stunDuration: Float = 0f,
    val stunCooldown: Float = 0f,
    /** Laser: total targets at level 1 (main + branches) and branch damage fraction. */
    val beamTargets: Int = 1,
    val beamSecondary: Float = 0f,
    /** Damage multiplier against Boss and Tank enemies (Heavy). */
    val bossBonus: Float = 1f,
    /** Raw cost of the level 1 -> 2 upgrade; see [UpgradeCosts]. */
    val upgradeBase: Int,
    /** Premium towers require the "unlock_premium_towers" purchase. */
    val premium: Boolean = false,
    val color: Int,
    /** One-word role shown in the build panel. */
    val role: String,
    val description: String,
) {
    ARROW(
        displayName = "Arrow", cost = 50, range = 2.6f, damage = 10f, fireRate = 1.4f,
        projectileSpeed = 9f, upgradeBase = 30, color = 0xFF8BC34A.toInt(), role = "Starter",
        description = "Cheap, reliable single-target damage."
    ),
    CANNON(
        displayName = "Cannon", cost = 100, range = 2.3f, damage = 26f, fireRate = 0.6f,
        projectileSpeed = 5f, splashRadius = 1.1f, upgradeBase = 60, color = 0xFFFF9800.toInt(),
        role = "Splash", description = "Slow shells that deal splash damage."
    ),
    FROST(
        displayName = "Frost", cost = 80, range = 2.2f, damage = 4f, fireRate = 1.0f,
        projectileSpeed = 7f, splashRadius = 0.6f, slowFactor = 0.5f, slowDuration = 1.6f,
        upgradeBase = 50, color = 0xFF4FC3F7.toInt(), role = "Area slow",
        description = "Chills a small area, slowing enemies by 50%."
    ),
    SLOW(
        displayName = "Slow", cost = 90, range = 2.5f, damage = 14f, fireRate = 0.8f,
        projectileSpeed = 2.6f, slowFactor = 0.65f, slowDuration = 2.5f,
        upgradeBase = 55, color = 0xFF26A69A.toInt(), role = "Slow on hit",
        description = "Sluggish goo shots: moderate damage, long single-target slow."
    ),
    RAPID(
        displayName = "Rapid", cost = 120, range = 2.2f, damage = 3f, fireRate = 8f,
        projectileSpeed = 12f, upgradeBase = 70, color = 0xFFEC407A.toInt(), role = "Swarm",
        description = "Very high fire rate, tiny bullets. Shreds swarms of weak enemies."
    ),
    HEAVY(
        displayName = "Heavy", cost = 175, range = 2.4f, damage = 85f, fireRate = 0.35f,
        projectileSpeed = 6f, bossBonus = 1.5f, upgradeBase = 100, color = 0xFF8D6E63.toInt(),
        role = "Boss killer", description = "Slow, massive single shots. Bonus damage vs Boss and Tank."
    ),
    DISRUPTOR(
        displayName = "Disruptor", cost = 140, range = 2.0f, damage = 0f, fireRate = 0.25f,
        projectileSpeed = 0f, attack = Attack.PULSE, stunDuration = 0.5f, stunCooldown = 4f,
        upgradeBase = 80, color = 0xFF3D5AFE.toInt(), role = "Stun",
        description = "No damage. Pulses to stun every enemy in range (bosses resist)."
    ),
    SNIPER(
        displayName = "Sniper", cost = 150, range = 5.5f, damage = 70f, fireRate = 0.4f,
        projectileSpeed = 22f, upgradeBase = 90, premium = true, color = 0xFF9C27B0.toInt(),
        role = "Long range", description = "Premium: huge range and heavy hits."
    ),
    RAILGUN(
        displayName = "Railgun", cost = 200, range = 3.0f, damage = 7f, fireRate = 6f,
        projectileSpeed = 0f, attack = Attack.INSTANT, upgradeBase = 120, premium = true,
        color = 0xFF00E5FF.toInt(), role = "Instant hit",
        description = "Premium: rapid instant-hit rail slugs."
    ),
    LASER(
        displayName = "Laser", cost = 250, range = 2.8f, damage = 22f, fireRate = 0f,
        projectileSpeed = 0f, attack = Attack.BEAM, beamTargets = 3, beamSecondary = 0.30f,
        upgradeBase = 150, premium = true, color = 0xFFF44336.toInt(), role = "Chain beam",
        description = "Premium: continuous beam that branches to up to 10 enemies."
    ),
    ANNIHILATOR(
        displayName = "Annihilator", cost = 400, range = 3.2f, damage = 600f, fireRate = 0.28f,
        projectileSpeed = 4.5f, splashRadius = 1.6f, upgradeBase = 240, premium = true,
        color = 0xFFAD1457.toInt(), role = "Nuke",
        description = "Premium: one shot every ~3.6 s with enormous splash damage."
    );
}
