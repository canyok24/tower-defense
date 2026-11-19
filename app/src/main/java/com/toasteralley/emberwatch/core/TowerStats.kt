package com.toasteralley.emberwatch.core

/** Fully resolved tower stats for one run. */
data class TowerStats(
    val maxHealth: Double,
    val damage: Double,
    val defense: Double,          // 0..0.9 damage reduction
    val fireRate: Double,         // shots/s
    val critChance: Double,       // may exceed 1 (guaranteed crit)
    val critFactor: Double,
    val superCritChance: Double,
    val superCritFactor: Double,
    val projectileSpeed: Double,  // playfield heights per second
    val lightSpeed: Boolean,
    val startingCash: Double,
    val enhancement: Double,
) {
    companion object {
        const val BASE_PROJECTILE_SPEED = 1.1

        /**
         * Resolution order: workshop base -> perks -> research -> enhancement.
         * The enhancement multiplier scales every multiplicative stat (health,
         * damage, crit/super-crit factor bonus, ultimate damage via damage).
         * Probability stats (defense, crit chance) and fire rate are NOT
         * enhanced, so they stay inside their caps.
         */
        fun resolve(save: SaveData): TowerStats {
            val enh = Enhancement.multiplier(save.enhancement)
            val p = { d: DiamondPerk -> save.level(d) }
            val r = { rp: ResearchProject -> save.level(rp) }

            val health = WorkshopStat.HEALTH.value(save.level(WorkshopStat.HEALTH)) *
                Math.pow(1.08, p(DiamondPerk.MULT_HEALTH).toDouble()) *
                ResearchProject.LAB_HEALTH.effect(r(ResearchProject.LAB_HEALTH)) * enh
            val dmg = WorkshopStat.ATTACK.value(save.level(WorkshopStat.ATTACK)) *
                Math.pow(1.08, p(DiamondPerk.MULT_DAMAGE).toDouble()) *
                ResearchProject.LAB_DAMAGE.effect(r(ResearchProject.LAB_DAMAGE)) * enh
            val critChance = WorkshopStat.CRIT_CHANCE.value(save.level(WorkshopStat.CRIT_CHANCE)) *
                (1 + 0.04 * p(DiamondPerk.MULT_CRIT_RATE)) + 0.005 * p(DiamondPerk.ADV_CRIT_RATE)
            val rawCritFactor = (WorkshopStat.CRIT_FACTOR.value(save.level(WorkshopStat.CRIT_FACTOR)) +
                0.15 * p(DiamondPerk.CRIT_FACTOR)) * Math.pow(1.05, p(DiamondPerk.ADV_CRIT_FACTOR).toDouble())
            val sc = p(DiamondPerk.SUPER_CRITS)
            val lightSpeed = r(ResearchProject.LIGHT_SPEED_SHOTS) > 0
            val velocityLvl = r(ResearchProject.PROJECTILE_VELOCITY)
            // Light Speed converts projectile-velocity research into pure fire rate.
            val velocityToRate = if (lightSpeed) 1 + 0.03 * velocityLvl else 1.0
            val fireRate = WorkshopStat.ATTACK_SPEED.value(save.level(WorkshopStat.ATTACK_SPEED)) *
                ResearchProject.LAB_ATTACK_SPEED.effect(r(ResearchProject.LAB_ATTACK_SPEED)) * velocityToRate
            return TowerStats(
                maxHealth = health,
                damage = dmg,
                defense = WorkshopStat.DEFENSE.value(save.level(WorkshopStat.DEFENSE)).coerceAtMost(0.9),
                fireRate = fireRate.coerceAtMost(40.0),
                critChance = critChance,
                critFactor = 1 + (rawCritFactor - 1) * enh,
                superCritChance = (0.01 * sc).coerceAtMost(0.5),
                superCritFactor = if (sc > 0) 1 + (1.0 + 0.25 * sc) * enh else 1.0,
                projectileSpeed = if (lightSpeed) Double.POSITIVE_INFINITY
                    else BASE_PROJECTILE_SPEED * ResearchProject.PROJECTILE_VELOCITY.effect(velocityLvl),
                lightSpeed = lightSpeed,
                startingCash = ResearchProject.STARTING_CASH.effect(r(ResearchProject.STARTING_CASH)),
                enhancement = enh,
            )
        }
    }
}
