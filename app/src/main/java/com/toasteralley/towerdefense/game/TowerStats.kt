package com.toasteralley.towerdefense.game

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Global (out-of-run) bonuses applied on top of a tower's level stats: meta
 * "Tokens" upgrades and the prestige damage multiplier. [NONE] = baseline.
 */
data class RunModifiers(
    /** Meta damage bonus, e.g. 1.10 = +10%. */
    val damageMult: Float = 1f,
    val fireRateMult: Float = 1f,
    val rangeMult: Float = 1f,
    /** Slow/stun duration and splash radius. */
    val effectMult: Float = 1f,
    /** Gold per kill (never wave rewards). */
    val killGoldMult: Float = 1f,
    val bonusStartGold: Int = 0,
    val bonusLives: Int = 0,
    /** Permanent prestige damage multiplier (Super Tokens spent on damage). */
    val prestigeMult: Float = 1f,
    /** Prestige Gold per Kill multiplier; stacks multiplicatively with [killGoldMult]. */
    val prestigeKillGoldMult: Float = 1f,
    /** Prestige Gold per Wave multiplier (end-of-wave gold). */
    val prestigeWaveGoldMult: Float = 1f,
) {
    companion object {
        val NONE = RunModifiers()
    }
}

/** Fully resolved combat stats of one tower at one level. */
data class TowerStats(
    val damage: Float,
    val range: Float,
    /** Shots per second (Disruptor: pulses per second; Laser: unused). */
    val fireRate: Float,
    val splashRadius: Float,
    val slowFactor: Float,
    val slowDuration: Float,
    val stunDuration: Float,
    val stunCooldown: Float,
    val beamTargets: Int,
    val beamSecondary: Float,
    val bossBonus: Float,
) {
    companion object {
        const val MAX_LEVEL = 20

        // ---- data-driven level curves (see README "In-run upgrades") ----
        /** Multiplicative damage growth per level. */
        private const val DAMAGE_GROWTH = 1.12f
        private const val ANNIHILATOR_DAMAGE_GROWTH = 1.10f
        /** Milestone bonuses (cumulative) at level 10 and 20. */
        const val L10_DAMAGE = 1.25f
        const val L20_DAMAGE = 1.40f
        private const val RATE_PER_LEVEL = 0.03f
        private const val L10_RATE = 1.10f
        private const val L20_RATE = 1.15f
        private const val MAX_RATE_FACTOR = 2.5f
        private const val ABS_MAX_RATE = 16f
        private const val RANGE_PER_LEVEL = 0.025f
        private const val MAX_RANGE_FACTOR = 1.7f
        private const val ABS_MAX_RANGE = 8f
        private const val SPLASH_PER_LEVEL = 0.02f
        private const val MAX_SPLASH_FACTOR = 1.7f
        private const val MIN_SLOW_FACTOR = 0.30f
        const val MAX_STUN = 1.5f
        const val MIN_STUN_COOLDOWN = 1.8f
        const val MAX_BEAM_TARGETS = 10
        const val MAX_BEAM_SECONDARY = 0.40f
        const val MAX_BOSS_BONUS = 2.5f

        /** Annihilator: slow until 13, then ramps to this many shots/s at 20. */
        const val ANNIHILATOR_RATE_L13_FACTOR = 1.18f
        const val ANNIHILATOR_RATE_L20 = 2.0f

        private fun m10(level: Int) = level >= 10
        private fun m20(level: Int) = level >= 20

        fun damageAt(type: TowerType, level: Int): Float {
            val g = if (type == TowerType.ANNIHILATOR) ANNIHILATOR_DAMAGE_GROWTH else DAMAGE_GROWTH
            var d = type.damage * g.pow(level - 1)
            if (m10(level)) d *= L10_DAMAGE
            if (m20(level)) d *= L20_DAMAGE
            return d
        }

        fun fireRateAt(type: TowerType, level: Int): Float {
            if (type == TowerType.ANNIHILATOR) {
                val r13 = type.fireRate * ANNIHILATOR_RATE_L13_FACTOR
                return if (level <= 13) {
                    type.fireRate * (1f + (ANNIHILATOR_RATE_L13_FACTOR - 1f) * (level - 1) / 12f)
                } else {
                    // Geometric ramp: 13 -> 20 multiplies the rate by ~6x.
                    r13 * (ANNIHILATOR_RATE_L20 / r13).pow((level - 13) / 7f)
                }
            }
            if (type.attack == Attack.PULSE) return 1f / stunCooldownAt(type, level)
            var r = type.fireRate * (1f + RATE_PER_LEVEL * (level - 1))
            if (m10(level)) r *= L10_RATE
            if (m20(level)) r *= L20_RATE
            return min(r, min(type.fireRate * MAX_RATE_FACTOR, ABS_MAX_RATE))
        }

        fun rangeAt(type: TowerType, level: Int): Float {
            var f = 1f + RANGE_PER_LEVEL * (level - 1)
            if (m10(level)) f += 0.08f
            if (m20(level)) f += 0.10f
            return min(type.range * min(f, MAX_RANGE_FACTOR), maxOf(ABS_MAX_RANGE, type.range))
        }

        fun splashAt(type: TowerType, level: Int): Float {
            if (type.splashRadius <= 0f) return 0f
            var f = 1f + SPLASH_PER_LEVEL * (level - 1)
            if (m10(level)) f += 0.10f
            if (m20(level)) f += 0.15f
            return type.splashRadius * min(f, MAX_SPLASH_FACTOR)
        }

        fun slowFactorAt(type: TowerType, level: Int): Float {
            if (type.slowFactor >= 1f) return 1f
            var s = type.slowFactor - 0.01f * (level - 1)
            if (m10(level)) s -= 0.05f
            if (m20(level)) s -= 0.05f
            return s.coerceAtLeast(MIN_SLOW_FACTOR)
        }

        fun slowDurationAt(type: TowerType, level: Int): Float {
            if (type.slowDuration <= 0f) return 0f
            var d = type.slowDuration * (1f + 0.03f * (level - 1))
            if (m10(level)) d *= 1.1f
            if (m20(level)) d *= 1.1f
            return min(d, type.slowDuration * 2f)
        }

        fun stunDurationAt(type: TowerType, level: Int): Float {
            if (type.stunDuration <= 0f) return 0f
            var d = type.stunDuration + 0.03f * (level - 1)
            if (m10(level)) d += 0.10f
            if (m20(level)) d += 0.20f
            return min(d, MAX_STUN)
        }

        fun stunCooldownAt(type: TowerType, level: Int): Float {
            if (type.stunCooldown <= 0f) return 0f
            var c = type.stunCooldown - 0.08f * (level - 1)
            if (m10(level)) c -= 0.30f
            if (m20(level)) c -= 0.40f
            return c.coerceAtLeast(MIN_STUN_COOLDOWN)
        }

        fun beamTargetsAt(type: TowerType, level: Int): Int {
            if (type.attack != Attack.BEAM) return 0
            var n = type.beamTargets + (level - 1) / 3
            if (m10(level)) n += 1
            if (m20(level)) n += 2
            return n.coerceAtMost(MAX_BEAM_TARGETS)
        }

        fun beamSecondaryAt(type: TowerType, level: Int): Float {
            if (type.attack != Attack.BEAM) return 0f
            var s = type.beamSecondary + 0.005f * (level - 1)
            if (m10(level)) s += 0.02f
            if (m20(level)) s += 0.03f
            return s.coerceAtMost(MAX_BEAM_SECONDARY)
        }

        fun bossBonusAt(type: TowerType, level: Int): Float {
            if (type.bossBonus <= 1f) return 1f
            var b = type.bossBonus + 0.025f * (level - 1)
            if (m10(level)) b += 0.15f
            if (m20(level)) b += 0.25f
            return b.coerceAtMost(MAX_BOSS_BONUS)
        }

        /** Level stats with global [mods] multiplied on top. */
        fun of(type: TowerType, level: Int, mods: RunModifiers = RunModifiers.NONE): TowerStats {
            val lvl = level.coerceIn(1, MAX_LEVEL)
            val stunCd = stunCooldownAt(type, lvl) / mods.fireRateMult
            return TowerStats(
                damage = damageAt(type, lvl) * mods.damageMult * mods.prestigeMult,
                range = rangeAt(type, lvl) * mods.rangeMult,
                fireRate = if (type.attack == Attack.PULSE) (if (stunCd > 0f) 1f / stunCd else 0f)
                else fireRateAt(type, lvl) * mods.fireRateMult,
                splashRadius = splashAt(type, lvl) * mods.effectMult,
                slowFactor = slowFactorAt(type, lvl),
                slowDuration = slowDurationAt(type, lvl) * mods.effectMult,
                stunDuration = stunDurationAt(type, lvl) * mods.effectMult,
                stunCooldown = stunCd,
                beamTargets = beamTargetsAt(type, lvl),
                beamSecondary = beamSecondaryAt(type, lvl),
                bossBonus = bossBonusAt(type, lvl),
            )
        }
    }
}

/**
 * Upgrade costs. Cost to reach level N (2..20), computed from the tower's
 * [TowerType.upgradeBase]:
 *  - levels 3..12: previous x [STEADY_GROWTH] (13% per level)
 *  - then vs previous level: L13 x3, L14 x3, L15 x1.2, L16 x1.2, L17 x1.2,
 *    L18 x3, L19 x1.2, L20 x1.2.
 * The exact (raw) value is rounded to a "nice" number with [niceRound].
 */
object UpgradeCosts {
    const val STEADY_GROWTH = 1.13
    /** Multiplier vs previous level for levels 13..20. */
    val LATE_MULTIPLIERS: Map<Int, Double> = mapOf(
        13 to 3.0, 14 to 3.0, 15 to 1.2, 16 to 1.2, 17 to 1.2, 18 to 3.0, 19 to 1.2, 20 to 1.2,
    )

    /** Multiplier from level N-1's cost to level N's cost (N in 3..20). */
    fun multiplierFor(level: Int): Double = LATE_MULTIPLIERS[level] ?: STEADY_GROWTH

    /** Unrounded cost to go from level-1 to [level]. */
    fun rawCostToReach(type: TowerType, level: Int): Double {
        require(level in 2..TowerStats.MAX_LEVEL) { "level $level out of range" }
        var c = type.upgradeBase.toDouble()
        for (l in 3..level) c *= multiplierFor(l)
        return c
    }

    /** Rounded gold cost to go from level-1 to [level]. */
    fun costToReach(type: TowerType, level: Int): Int = niceRound(rawCostToReach(type, level))

    /** Sum of costs to go from [fromLevel] up to [toLevel] (inclusive of each step). */
    fun costRange(type: TowerType, fromLevel: Int, toLevel: Int): Int {
        var sum = 0
        for (l in fromLevel + 1..toLevel) sum += costToReach(type, l)
        return sum
    }

    /** < 100: nearest 5; < 1000: nearest 10; < 10000: nearest 50; else nearest 100. */
    fun niceRound(v: Double): Int {
        val step = when {
            v < 100 -> 5
            v < 1_000 -> 10
            v < 10_000 -> 50
            else -> 100
        }
        return ((v / step).roundToInt() * step).coerceAtLeast(step)
    }
}
