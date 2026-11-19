package com.toasteralley.emberwatch.core

import kotlin.math.pow

/** Fully resolved tower stats (permanent + in-run workshop levels, perks, research, enhancement). */
data class TowerStats(
    val maxHealth: Double,
    val damage: Double,
    val defense: Double,          // 0..0.9 damage reduction
    val defenseAbs: Double,       // flat reduction after defense %
    val fireRate: Double,         // shots/s
    val critChance: Double,       // may exceed 1 (guaranteed crit)
    val critFactor: Double,
    val superCritChance: Double,  // Diamond Perk and Workshop combined: 1-(1-a)(1-b)
    val superCritFactor: Double,  // Diamond Perk factor x Workshop factor
    val projectileSpeed: Double,  // playfield heights per second
    val lightSpeed: Boolean,
    val startingCash: Double,
    val enhancement: Double,
    // Assault extras
    val range: Double,
    val multishotChance: Double, val multishotTargets: Int,
    val rapidChance: Double, val rapidMult: Double,
    val bounceChance: Double, val bounces: Int,
    val rendPerHit: Double, val rendMax: Double,
    // Shield extras
    val regenPerSec: Double,      // flat HP per second (Core Repair, scaled like max health), before the ceiling
    val thorns: Double,
    val lifesteal: Double,
    val knockbackChance: Double, val knockbackForce: Double,
    val orbs: Int, val orbDamage: Double,
    val shockPeriod: Double, val shockDamage: Double,
    val deathDefy: Double,
    val wallMaxHp: Double, val wallRebuildSec: Double, val wallRegenPerSec: Double,
    val mineInterval: Double, val mineDamage: Double,
    // Ledger
    val bounty: Double, val tribute: Double, val interest: Double,
    val freeChance: Map<WorkshopTab, Double>,
    val degradeChance: Double, val degradeMaxWaves: Int,
    val recoveryAmount: Double, val maxRecovery: Double,
) {
    /** Core Repair after its ceiling: min(flat HP/s, 2.8% of [maxHp] per second). */
    fun effectiveRegen(maxHp: Double = maxHealth) = minOf(regenPerSec, Upgrade.CORE_REPAIR_MAX_FRACTION * maxHp)

    companion object {
        const val BASE_PROJECTILE_SPEED = 1.1
        const val RAPID_FIRE_SECONDS = 2f
        /** Tower fire-rate ceiling after research (workshop alone reaches 40/s). */
        const val MAX_FIRE_RATE = 60.0

        /**
         * Resolution: workshop level = permanent + [runLevels] (in-run, indexed by
         * Upgrade.ordinal). Then perks -> research -> enhancement. Enhancement scales
         * health, damage (and everything derived from damage: orbs, shockwave, mines,
         * ultimates), the crit / super-crit bonus parts. Percent stats keep their caps.
         */
        fun resolve(save: SaveData, runLevels: IntArray? = null): TowerStats {
            fun lv(u: Upgrade) = (save.level(u) + (runLevels?.get(u.ordinal) ?: 0)).coerceAtMost(u.maxLevel)
            val enh = Enhancement.multiplier(save.enhancement)
            val p = { d: DiamondPerk -> save.level(d) }
            val r = { rp: ResearchProject -> save.level(rp) }

            // Same multipliers for Hull Integrity and Core Repair (research, perk, enhancement).
            val healthMult = 1.08.pow(p(DiamondPerk.MULT_HEALTH)) *
                ResearchProject.LAB_HEALTH.effect(r(ResearchProject.LAB_HEALTH)) * enh
            val health = Upgrade.HULL_INTEGRITY.value(lv(Upgrade.HULL_INTEGRITY)) * healthMult
            val dmg = Upgrade.DAMAGE.value(lv(Upgrade.DAMAGE)) *
                1.08.pow(p(DiamondPerk.MULT_DAMAGE)) *
                ResearchProject.LAB_DAMAGE.effect(r(ResearchProject.LAB_DAMAGE)) * enh
            val critChance = Upgrade.CRIT_CHANCE.value(lv(Upgrade.CRIT_CHANCE)) *
                (1 + 0.04 * p(DiamondPerk.MULT_CRIT_RATE)) + 0.005 * p(DiamondPerk.ADV_CRIT_RATE)
            val rawCritFactor = (Upgrade.CRIT_FACTOR.value(lv(Upgrade.CRIT_FACTOR)) +
                0.15 * p(DiamondPerk.CRIT_FACTOR)) * 1.05.pow(p(DiamondPerk.ADV_CRIT_FACTOR))
            val sc = p(DiamondPerk.SUPER_CRITS)
            val perkSuperChance = (0.01 * sc).coerceAtMost(0.5)
            val perkSuperFactor = if (sc > 0) 1 + (1.0 + 0.25 * sc) * enh else 1.0
            val wsl = lv(Upgrade.SUPER_CRIT)
            val wsSuperChance = Upgrade.SUPER_CRIT.value(wsl)
            val wsSuperFactor = if (wsl > 0) 1 + (Upgrade.SUPER_CRIT.secondary(wsl) - 1) * enh else 1.0
            val lightSpeed = r(ResearchProject.LIGHT_SPEED_SHOTS) > 0
            val velocityLvl = r(ResearchProject.PROJECTILE_VELOCITY)
            val velocityToRate = if (lightSpeed) 1 + 0.03 * velocityLvl else 1.0
            val fireRate = Upgrade.ATTACK_SPEED.value(lv(Upgrade.ATTACK_SPEED)) *
                ResearchProject.LAB_ATTACK_SPEED.effect(r(ResearchProject.LAB_ATTACK_SPEED)) * velocityToRate
            fun v(u: Upgrade) = u.value(lv(u))
            fun s2(u: Upgrade) = u.secondary(lv(u))
            return TowerStats(
                maxHealth = health,
                damage = dmg,
                defense = v(Upgrade.BARRIER).coerceAtMost(0.9),
                defenseAbs = v(Upgrade.PLATING),
                fireRate = fireRate.coerceAtMost(MAX_FIRE_RATE),
                critChance = critChance,
                critFactor = 1 + (rawCritFactor - 1) * enh,
                superCritChance = 1 - (1 - perkSuperChance) * (1 - wsSuperChance),
                superCritFactor = perkSuperFactor * wsSuperFactor,
                projectileSpeed = if (lightSpeed) Double.POSITIVE_INFINITY
                    else BASE_PROJECTILE_SPEED * ResearchProject.PROJECTILE_VELOCITY.effect(velocityLvl),
                lightSpeed = lightSpeed,
                startingCash = ResearchProject.STARTING_CASH.effect(r(ResearchProject.STARTING_CASH)),
                enhancement = enh,
                range = v(Upgrade.RANGE),
                multishotChance = v(Upgrade.MULTISHOT), multishotTargets = s2(Upgrade.MULTISHOT).toInt(),
                rapidChance = v(Upgrade.RAPID_FIRE), rapidMult = s2(Upgrade.RAPID_FIRE),
                bounceChance = v(Upgrade.BOUNCE_SHOT), bounces = s2(Upgrade.BOUNCE_SHOT).toInt(),
                rendPerHit = v(Upgrade.REND_ARMOR), rendMax = s2(Upgrade.REND_ARMOR),
                regenPerSec = v(Upgrade.CORE_REPAIR) * healthMult,
                thorns = v(Upgrade.COUNTERFIRE),
                lifesteal = v(Upgrade.DRAIN),
                knockbackChance = v(Upgrade.REPULSE), knockbackForce = s2(Upgrade.REPULSE),
                orbs = v(Upgrade.SATELLITES).toInt(), orbDamage = dmg * s2(Upgrade.SATELLITES),
                shockPeriod = v(Upgrade.PULSE), shockDamage = dmg * s2(Upgrade.PULSE),
                deathDefy = v(Upgrade.LAST_STAND),
                wallMaxHp = health * v(Upgrade.FORTIFICATION),
                wallRebuildSec = v(Upgrade.RECONSTRUCTION), wallRegenPerSec = s2(Upgrade.RECONSTRUCTION),
                mineInterval = v(Upgrade.CHARGES), mineDamage = dmg * s2(Upgrade.CHARGES),
                bounty = v(Upgrade.BOUNTY), tribute = v(Upgrade.TRIBUTE), interest = v(Upgrade.INTEREST),
                freeChance = WorkshopTab.entries.associateWith { v(Upgrade.freeUpgradeFor(it)) },
                degradeChance = v(Upgrade.ENEMY_DEGRADE), degradeMaxWaves = s2(Upgrade.ENEMY_DEGRADE).toInt(),
                recoveryAmount = v(Upgrade.RECOVERY_AMOUNT), maxRecovery = v(Upgrade.MAX_RECOVERY),
            )
        }
    }
}
