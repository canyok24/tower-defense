package com.toasteralley.towerdefense.game

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

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
        const val MAX_LEVEL = 100
        /** Levels 1..[EARLY_CAP] use the original (steep) curves; later levels are flattened. */
        const val EARLY_CAP = 20

        // ---- data-driven level curves (see README "In-run upgrades") ----
        /** Multiplicative damage growth per level up to level 20. */
        private const val DAMAGE_GROWTH = 1.12
        private const val ANNIHILATOR_DAMAGE_GROWTH = 1.10
        /** Past level 20 the per-level growth tapers exponentially toward this value. */
        const val LATE_DAMAGE_GROWTH = 1.035
        /** Taper length (levels) of the late damage growth. */
        private const val DAMAGE_TAPER = 15.0
        /** Milestone bonuses (cumulative) at level 10 and 20. */
        const val L10_DAMAGE = 1.25f
        const val L20_DAMAGE = 1.40f
        /** Smaller milestone bonus at each of levels 30, 40, ... 100. */
        const val LATE_MILESTONE_DAMAGE = 1.08f
        private const val RATE_PER_LEVEL = 0.03f
        private const val LATE_RATE_PER_LEVEL = 0.01f
        private const val L10_RATE = 1.10f
        private const val L20_RATE = 1.15f
        private const val LATE_MILESTONE_RATE = 1.02f
        const val MAX_RATE_FACTOR = 2.5f
        const val ABS_MAX_RATE = 16f
        private const val RANGE_PER_LEVEL = 0.025f
        private const val LATE_RANGE_PER_LEVEL = 0.005f
        const val MAX_RANGE_FACTOR = 1.7f
        const val ABS_MAX_RANGE = 8f
        private const val SPLASH_PER_LEVEL = 0.02f
        private const val LATE_SPLASH_PER_LEVEL = 0.005f
        const val MAX_SPLASH_FACTOR = 1.7f
        const val MIN_SLOW_FACTOR = 0.30f
        const val MAX_SLOW_DURATION_FACTOR = 2f
        const val MAX_STUN = 1.5f
        const val MIN_STUN_COOLDOWN = 1.8f
        const val MAX_BEAM_TARGETS = 10
        const val MAX_BEAM_SECONDARY = 0.40f
        const val MAX_BOSS_BONUS = 2.5f

        /**
         * Annihilator fire rate: slow ramp to x1.18 at level 13, gentle linear
         * ramp to x[ANNIHILATOR_RATE_L80_FACTOR] (of the level-13 rate) at level
         * 80, then the surge: geometric ramp to [ANNIHILATOR_MAX_RATE] at level 100.
         * The rate is clamped AFTER global modifiers, so the shot gap is never
         * below [ANNIHILATOR_MIN_SHOT_GAP] seconds.
         */
        const val ANNIHILATOR_RATE_L13_FACTOR = 1.18f
        const val ANNIHILATOR_RATE_L80_FACTOR = 1.5f
        const val ANNIHILATOR_SURGE_LEVEL = 80
        const val ANNIHILATOR_MIN_SHOT_GAP = 0.4f
        const val ANNIHILATOR_MAX_RATE = 1f / ANNIHILATOR_MIN_SHOT_GAP

        private fun m10(level: Int) = level >= 10
        private fun m20(level: Int) = level >= 20

        /** Number of late milestones (30, 40, ... 100) reached at [level]. */
        fun lateMilestones(level: Int): Int = (level.coerceAtMost(MAX_LEVEL) / 10 - 2).coerceAtLeast(0)

        /** True for milestone levels 10, 20, ..., 100. */
        fun isMilestone(level: Int): Boolean = level % 10 == 0 && level in 10..MAX_LEVEL

        /** Levels gained within the early (1..20) band and the late (21..) band. */
        private fun early(level: Int) = (level.coerceAtMost(EARLY_CAP) - 1).coerceAtLeast(0)
        private fun late(level: Int) = (level - EARLY_CAP).coerceAtLeast(0)

        /** Growth factor applied when going from level-1 to [level]. */
        fun damageGrowthAt(type: TowerType, level: Int): Double {
            val g0 = if (type == TowerType.ANNIHILATOR) ANNIHILATOR_DAMAGE_GROWTH else DAMAGE_GROWTH
            if (level <= EARLY_CAP) return g0
            return LATE_DAMAGE_GROWTH + (g0 - LATE_DAMAGE_GROWTH) * exp(-(level - EARLY_CAP) / DAMAGE_TAPER)
        }

        private val damageCache = HashMap<TowerType, DoubleArray>()

        /** Level-curve damage multiplier (without base damage), cached per tower type. */
        private fun damageFactor(type: TowerType, level: Int): Double {
            val arr = synchronized(damageCache) {
                damageCache.getOrPut(type) {
                    DoubleArray(MAX_LEVEL + 1).also { a ->
                        a[1] = 1.0
                        for (l in 2..MAX_LEVEL) a[l] = a[l - 1] * damageGrowthAt(type, l)
                    }
                }
            }
            var d = arr[level.coerceIn(1, MAX_LEVEL)]
            if (m10(level)) d *= L10_DAMAGE
            if (m20(level)) d *= L20_DAMAGE
            d *= LATE_MILESTONE_DAMAGE.toDouble().pow(lateMilestones(level))
            return d
        }

        fun damageAt(type: TowerType, level: Int): Float = (type.damage * damageFactor(type, level)).toFloat()

        fun fireRateAt(type: TowerType, level: Int): Float {
            if (type == TowerType.ANNIHILATOR) return annihilatorRate(level)
            if (type.attack == Attack.PULSE) return 1f / stunCooldownAt(type, level)
            var r = type.fireRate * (1f + RATE_PER_LEVEL * early(level) + LATE_RATE_PER_LEVEL * late(level))
            if (m10(level)) r *= L10_RATE
            if (m20(level)) r *= L20_RATE
            r *= LATE_MILESTONE_RATE.pow(lateMilestones(level))
            return min(r, min(type.fireRate * MAX_RATE_FACTOR, ABS_MAX_RATE))
        }

        private fun annihilatorRate(level: Int): Float {
            val base = TowerType.ANNIHILATOR.fireRate
            val r13 = base * ANNIHILATOR_RATE_L13_FACTOR
            val r80 = r13 * ANNIHILATOR_RATE_L80_FACTOR
            val r = when {
                level <= 13 -> base * (1f + (ANNIHILATOR_RATE_L13_FACTOR - 1f) * (level - 1) / 12f)
                level <= ANNIHILATOR_SURGE_LEVEL ->
                    r13 * (1f + (ANNIHILATOR_RATE_L80_FACTOR - 1f) * (level - 13) / (ANNIHILATOR_SURGE_LEVEL - 13f))
                else -> r80 * (ANNIHILATOR_MAX_RATE / r80).pow(
                    (level - ANNIHILATOR_SURGE_LEVEL).toFloat() / (MAX_LEVEL - ANNIHILATOR_SURGE_LEVEL)
                )
            }
            return min(r, ANNIHILATOR_MAX_RATE)
        }

        fun rangeAt(type: TowerType, level: Int): Float {
            var f = 1f + RANGE_PER_LEVEL * early(level) + LATE_RANGE_PER_LEVEL * late(level)
            if (m10(level)) f += 0.08f
            if (m20(level)) f += 0.10f
            f += 0.02f * lateMilestones(level)
            return min(type.range * min(f, MAX_RANGE_FACTOR), maxOf(ABS_MAX_RANGE, type.range))
        }

        fun splashAt(type: TowerType, level: Int): Float {
            if (type.splashRadius <= 0f) return 0f
            var f = 1f + SPLASH_PER_LEVEL * early(level) + LATE_SPLASH_PER_LEVEL * late(level)
            if (m10(level)) f += 0.10f
            if (m20(level)) f += 0.15f
            f += 0.03f * lateMilestones(level)
            return type.splashRadius * min(f, MAX_SPLASH_FACTOR)
        }

        fun slowFactorAt(type: TowerType, level: Int): Float {
            if (type.slowFactor >= 1f) return 1f
            var s = type.slowFactor - 0.01f * early(level) - 0.002f * late(level)
            if (m10(level)) s -= 0.05f
            if (m20(level)) s -= 0.05f
            s -= 0.01f * lateMilestones(level)
            return s.coerceAtLeast(MIN_SLOW_FACTOR)
        }

        fun slowDurationAt(type: TowerType, level: Int): Float {
            if (type.slowDuration <= 0f) return 0f
            var d = type.slowDuration * (1f + 0.03f * early(level) + 0.01f * late(level))
            if (m10(level)) d *= 1.1f
            if (m20(level)) d *= 1.1f
            d *= 1.03f.pow(lateMilestones(level))
            return min(d, type.slowDuration * MAX_SLOW_DURATION_FACTOR)
        }

        fun stunDurationAt(type: TowerType, level: Int): Float {
            if (type.stunDuration <= 0f) return 0f
            var d = type.stunDuration + 0.03f * early(level) + 0.005f * late(level)
            if (m10(level)) d += 0.10f
            if (m20(level)) d += 0.20f
            d += 0.03f * lateMilestones(level)
            return min(d, MAX_STUN)
        }

        fun stunCooldownAt(type: TowerType, level: Int): Float {
            if (type.stunCooldown <= 0f) return 0f
            var c = type.stunCooldown - 0.08f * early(level) - 0.01f * late(level)
            if (m10(level)) c -= 0.30f
            if (m20(level)) c -= 0.40f
            c -= 0.05f * lateMilestones(level)
            return c.coerceAtLeast(MIN_STUN_COOLDOWN)
        }

        fun beamTargetsAt(type: TowerType, level: Int): Int {
            if (type.attack != Attack.BEAM) return 0
            var n = type.beamTargets + early(level) / 3 + late(level) / 20
            if (m10(level)) n += 1
            if (m20(level)) n += 2
            return n.coerceAtMost(MAX_BEAM_TARGETS)
        }

        fun beamSecondaryAt(type: TowerType, level: Int): Float {
            if (type.attack != Attack.BEAM) return 0f
            var s = type.beamSecondary + 0.005f * early(level) + 0.001f * late(level)
            if (m10(level)) s += 0.02f
            if (m20(level)) s += 0.03f
            s += 0.005f * lateMilestones(level)
            return s.coerceAtMost(MAX_BEAM_SECONDARY)
        }

        fun bossBonusAt(type: TowerType, level: Int): Float {
            if (type.bossBonus <= 1f) return 1f
            var b = type.bossBonus + 0.025f * early(level) + 0.005f * late(level)
            if (m10(level)) b += 0.15f
            if (m20(level)) b += 0.25f
            b += 0.03f * lateMilestones(level)
            return b.coerceAtMost(MAX_BOSS_BONUS)
        }

        /** Level stats with global [mods] multiplied on top. */
        fun of(type: TowerType, level: Int, mods: RunModifiers = RunModifiers.NONE): TowerStats {
            val lvl = level.coerceIn(1, MAX_LEVEL)
            val stunCd = stunCooldownAt(type, lvl) / mods.fireRateMult
            return TowerStats(
                damage = damageAt(type, lvl) * mods.damageMult * mods.prestigeMult,
                range = rangeAt(type, lvl) * mods.rangeMult,
                fireRate = when {
                    type.attack == Attack.PULSE -> if (stunCd > 0f) 1f / stunCd else 0f
                    // Firm minimum shot gap, even with meta Attack Speed on top.
                    type == TowerType.ANNIHILATOR -> min(fireRateAt(type, lvl) * mods.fireRateMult, ANNIHILATOR_MAX_RATE)
                    else -> fireRateAt(type, lvl) * mods.fireRateMult
                },
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
 * Upgrade costs. Cost to reach level N (2..[TowerStats.MAX_LEVEL]) is computed
 * from the tower's [TowerType.upgradeBase] as a running product of per-level
 * multipliers on the UNROUNDED cost:
 *  - levels 3..12: previous x [STEADY_GROWTH] (13% per level)
 *  - levels 13, 14, 18, 28, 38, 48: previous x [JUMP] (3)
 *  - every other level 15..100: previous x [LATE_GROWTH] (1.2)
 * Only the charged price is rounded ([niceRound]); everything is Double/Long.
 */
object UpgradeCosts {
    const val STEADY_GROWTH = 1.13
    const val JUMP = 3.0
    const val LATE_GROWTH = 1.2
    val JUMP_LEVELS: Set<Int> = setOf(13, 14, 18, 28, 38, 48)

    /** Multiplier from level N-1's cost to level N's cost (N in 3..MAX_LEVEL). */
    fun multiplierFor(level: Int): Double = when {
        level in JUMP_LEVELS -> JUMP
        level <= 12 -> STEADY_GROWTH
        else -> LATE_GROWTH
    }

    private val rawCache = HashMap<TowerType, DoubleArray>()
    private val prefixCache = HashMap<TowerType, LongArray>()

    private fun rawTable(type: TowerType): DoubleArray = synchronized(rawCache) {
        rawCache.getOrPut(type) {
            DoubleArray(TowerStats.MAX_LEVEL + 1).also { a ->
                a[2] = type.upgradeBase.toDouble()
                for (l in 3..TowerStats.MAX_LEVEL) a[l] = a[l - 1] * multiplierFor(l)
            }
        }
    }

    /** prefix[l] = rounded gold to go from level 1 to level l. */
    private fun prefix(type: TowerType): LongArray = synchronized(prefixCache) {
        prefixCache.getOrPut(type) {
            val raw = rawTable(type)
            LongArray(TowerStats.MAX_LEVEL + 1).also { p ->
                for (l in 2..TowerStats.MAX_LEVEL) p[l] = p[l - 1] + niceRound(raw[l])
            }
        }
    }

    /** Unrounded cost to go from level-1 to [level]. */
    fun rawCostToReach(type: TowerType, level: Int): Double {
        require(level in 2..TowerStats.MAX_LEVEL) { "level $level out of range" }
        return rawTable(type)[level]
    }

    /** Rounded (charged) gold cost to go from level-1 to [level]. */
    fun costToReach(type: TowerType, level: Int): Long = niceRound(rawCostToReach(type, level))

    /** Sum of costs to go from [fromLevel] up to [toLevel] (inclusive of each step). */
    fun costRange(type: TowerType, fromLevel: Int, toLevel: Int): Long {
        val from = fromLevel.coerceIn(1, TowerStats.MAX_LEVEL)
        val to = toLevel.coerceIn(1, TowerStats.MAX_LEVEL)
        if (to <= from) return 0L
        val p = prefix(type)
        return p[to] - p[from]
    }

    /**
     * < 100: nearest 5; < 1,000: nearest 10; < 10,000: nearest 50;
     * < 1,000,000: nearest 100; above: 4 significant digits.
     */
    fun niceRound(v: Double): Long {
        val step: Double = when {
            v < 100 -> 5.0
            v < 1_000 -> 10.0
            v < 10_000 -> 50.0
            v < 1_000_000 -> 100.0
            else -> 10.0.pow(floor(log10(v)) - 3)
        }
        return max(step, (v / step).roundToLong() * step).toLong()
    }
}
