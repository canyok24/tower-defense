package com.toasteralley.towerdefense.game

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One scheduled spawn: wait [delay] seconds, then spawn [type] with [hp]
 * hit points that pays [bounty] base gold when killed (before meta/prestige
 * multipliers).
 */
data class SpawnEntry(
    val type: EnemyType,
    val delay: Float,
    val hp: Double = type.baseHp.toDouble(),
    val bounty: Long = type.reward.toLong(),
    val speedMult: Float = 1f,
)

/**
 * A fully resolved wave: spawns plus the economy numbers behind them.
 * All big quantities are Double/Long so nothing overflows at wave 100,000+.
 */
data class WavePlan(
    val wave: Int,
    val spawns: List<SpawnEntry>,
    /** Gold paid when the wave is cleared (before prestige multiplier). */
    val waveGold: Long,
    /** Sum of every enemy's HP. */
    val effectiveHp: Double,
    /** Seconds over which the wave must be killed (spawn time + engage time). */
    val duration: Double,
    /** Gold of Arrow investment needed to deliver [effectiveHp] in [duration]. */
    val costToKill: Double,
    /** Designed income / costToKill for this wave. */
    val targetRatio: Double,
    val enemyHpMultiplier: Double,
    val speedMult: Float,
) {
    val killGold: Long get() = spawns.sumOf { it.bounty }
    /** Base income of the wave: all kill bounties + wave gold. */
    val income: Long get() = killGold + waveGold
    /** Measured income / costToKill (uses the rounded gold actually paid). */
    val ratio: Double get() = income / costToKill
    val enemyCount: Int get() = spawns.size
    val bossCount: Int get() = spawns.count { it.type == EnemyType.BOSS }
}

/**
 * Endless waves and the gold economy. Waves 1-20 keep their handcrafted
 * composition; from wave 21 on, waves are generated procedurally forever.
 * Clearing wave [MILESTONE_WAVE] is the "victory" milestone - the run then
 * continues. See README "Economy" for the design and numbers.
 *
 * Economy: every wave has a measurable
 *   costToKill(w) = (EHP(w) / duration(w)) / referenceDpsPerGold
 * i.e. the gold you would have to invest in the reference tower (Arrow, at its
 * most gold-efficient level) to deliver the wave's total HP during the wave.
 * Base income(w) = kill bounties + wave gold is set to targetRatio(w) x
 * costToKill(w): ~5x at wave 1, still >2x at wave 100, ~1x at wave 1000, and
 * slowly below parity afterwards. 75% of it is paid as kill bounties
 * (proportional to each enemy's HP), 25% (+rounding) as end-of-wave gold.
 */
object WaveDefinitions {
    /** Clearing this wave is the victory milestone; the run continues. */
    const val MILESTONE_WAVE = 20
    /** Last handcrafted wave; later waves are procedural. */
    const val HANDCRAFTED_WAVES = 20
    const val FIRST_WAVE_COUNTDOWN = 20f
    const val BETWEEN_WAVE_COUNTDOWN = 12f
    const val STARTING_GOLD = 220
    const val STARTING_LIVES = 20

    // ---- procedural limits
    /** Max non-boss enemies spawned per wave; extra count turns into HP. */
    const val MAX_NON_BOSS = 140
    /** Max bosses per wave; extra bosses turn into boss HP. */
    const val MAX_BOSSES = 6
    const val SPAWN_INTERVAL_FLOOR = 0.25f
    const val SPEED_CAP = 1.4f
    /** Per-enemy HP clamp (keeps Float rendering/Double math finite). */
    const val MAX_ENEMY_HP = 1e30
    /** Gold clamp (well below Long.MAX and exactly representable in Double). */
    const val MAX_GOLD = 4_000_000_000_000_000L

    // ---- HP curve
    private const val EARLY_HP_GROWTH = 1.14
    /** Late HP: hp(20) x (1 + (w-20)/C)^P. */
    private const val LATE_HP_C = 61.5
    private const val LATE_HP_P = 2.0

    // ---- economy
    const val KILL_SHARE = 0.75
    const val RATIO_START = 5.0
    const val PARITY_WAVE = 1000
    private const val LATE_RATIO_DECAY = 0.1

    /** Seconds a normal enemy spends inside a reference Arrow's range. */
    val ENGAGE_SECONDS: Double = 2.0 * TowerType.ARROW.range / EnemyType.NORMAL.speed

    /**
     * DPS bought per gold by the reference tower (Arrow) at its most
     * gold-efficient level: max over L of dps(L) / (build + upgrades to L).
     */
    val REFERENCE_DPS_PER_GOLD: Double by lazy {
        (1..TowerStats.MAX_LEVEL).maxOf { l ->
            val s = TowerStats.of(TowerType.ARROW, l)
            s.damage.toDouble() * s.fireRate / (TowerType.ARROW.cost + UpgradeCosts.costRange(TowerType.ARROW, 1, l))
        }
    }

    /** Enemy HP multiplier for [wave] (1-based), finite for any Int wave. */
    fun hpMultiplier(wave: Int): Double {
        val w = wave.coerceAtLeast(1)
        if (w <= HANDCRAFTED_WAVES) return EARLY_HP_GROWTH.pow(w - 1)
        val h20 = EARLY_HP_GROWTH.pow(HANDCRAFTED_WAVES - 1)
        return h20 * (1.0 + (w - HANDCRAFTED_WAVES) / LATE_HP_C).pow(LATE_HP_P)
    }

    /** Enemy speed multiplier: 1 through wave 20, then log growth capped at [SPEED_CAP]. */
    fun speedMultiplier(wave: Int): Float =
        if (wave <= HANDCRAFTED_WAVES) 1f
        else min(SPEED_CAP.toDouble(), 1.0 + 0.08 * ln(wave.toDouble() / HANDCRAFTED_WAVES)).toFloat()

    /**
     * Designed income / costToKill: 1 + 4(1 - ln w / ln 1000) up to wave 1000
     * (5.0 at w=1, 2.33 at w=100, 1.0 at w=1000), then 1 / (1 + 0.1 ln(w/1000)).
     * Non-increasing in w.
     */
    fun targetRatio(wave: Int): Double {
        val w = wave.coerceAtLeast(1).toDouble()
        return if (w <= PARITY_WAVE) 1.0 + (RATIO_START - 1.0) * (1.0 - ln(w) / ln(PARITY_WAVE.toDouble()))
        else 1.0 / (1.0 + LATE_RATIO_DECAY * ln(w / PARITY_WAVE))
    }

    fun spawnInterval(wave: Int): Float =
        if (wave <= HANDCRAFTED_WAVES) (0.9f - wave * 0.025f).coerceAtLeast(0.35f)
        else max(SPAWN_INTERVAL_FLOOR, 0.35f - 0.0002f * (wave - HANDCRAFTED_WAVES).coerceAtMost(1000))

    /** Spawn list for [wave] (HP and bounties included). */
    fun build(wave: Int): List<SpawnEntry> = plan(wave).spawns

    private class Group(val type: EnemyType, val rawCount: Double, val delay: Float, val firstDelay: Float = delay)

    private fun groups(wave: Int): List<Group> {
        val w = wave.toDouble()
        val interval = spawnInterval(wave)
        val list = mutableListOf<Group>()
        if (wave <= HANDCRAFTED_WAVES) {
            list += Group(EnemyType.NORMAL, 5.0 + wave * 2, interval)
            if (wave >= 3) list += Group(EnemyType.FAST, 2.0 + (wave - 3) * 2, interval * 0.6f, 1.5f)
            if (wave >= 5) list += Group(EnemyType.TANK, 1.0 + (wave - 5) / 2, interval * 1.8f)
            if (wave % 5 == 0) list += Group(EnemyType.BOSS, (wave / 10 + 1).toDouble(), 2.5f)
            return list
        }
        // Procedural: same base formulas, with a deterministic mix rotation.
        var normal = 5.0 + 2.0 * w
        var fast = 2.0 + 2.0 * (w - 3)
        var tank = 1.0 + floor((w - 5) / 2)
        when {
            wave % 11 == 0 -> { tank *= 2.5; normal *= 0.6 }   // armored wave
            wave % 7 == 0 -> { fast *= 1.5; normal *= 0.7 }    // rush wave
            wave % 3 == 0 -> { normal *= 1.2; fast *= 0.85 }   // swarm wave
        }
        list += Group(EnemyType.NORMAL, normal, interval)
        list += Group(EnemyType.FAST, fast, interval * 0.6f, 1.5f)
        list += Group(EnemyType.TANK, tank, interval * 1.8f)
        // Bosses every 5 waves, more on every 10th.
        if (wave % 10 == 0) list += Group(EnemyType.BOSS, floor(w / 10) + 1, 2.5f)
        else if (wave % 5 == 0) list += Group(EnemyType.BOSS, floor(w / 20) + 1, 2.5f)
        return list
    }

    /** Resolves [wave] into spawns, HP, bounties and economy figures. */
    fun plan(wave: Int): WavePlan {
        val w = wave.coerceAtLeast(1)
        val groups = groups(w)
        val hpMult = hpMultiplier(w)
        val speed = speedMultiplier(w)

        // Clamp counts; excess count becomes HP so total HP is preserved.
        val nonBossRaw = groups.filter { it.type != EnemyType.BOSS }.sumOf { it.rawCount }
        val nonBossScale = if (nonBossRaw > MAX_NON_BOSS) MAX_NON_BOSS / nonBossRaw else 1.0
        data class Sized(val g: Group, val count: Int, val hpScale: Double)
        val sized = groups.mapNotNull { g ->
            if (g.rawCount < 1.0) return@mapNotNull null
            val target = if (g.type == EnemyType.BOSS) min(g.rawCount, MAX_BOSSES.toDouble())
            else g.rawCount * nonBossScale
            val count = max(1L, target.roundToLong()).toInt()
            Sized(g, count, g.rawCount / count)
        }

        val hps = sized.map { s -> min(MAX_ENEMY_HP, s.g.type.baseHp * hpMult * s.hpScale) }
        val ehp = sized.indices.sumOf { hps[it] * sized[it].count }
        val spawnTime = sized.sumOf { s -> s.g.firstDelay.toDouble() + s.g.delay.toDouble() * (s.count - 1) }
        val duration = spawnTime + ENGAGE_SECONDS / speed
        val costToKill = ehp / duration / REFERENCE_DPS_PER_GOLD
        val ratio = targetRatio(w)
        val income = min(MAX_GOLD.toDouble(), ratio * costToKill)
        val killPool = KILL_SHARE * income

        val spawns = ArrayList<SpawnEntry>(sized.sumOf { it.count })
        var bountyTotal = 0L
        sized.forEachIndexed { i, s ->
            val bounty = max(1L, floor(killPool * hps[i] / ehp).toLong())
            repeat(s.count) { n ->
                spawns += SpawnEntry(s.g.type, if (n == 0) s.g.firstDelay else s.g.delay, hps[i], bounty, speed)
                bountyTotal += bounty
            }
        }
        val waveGold = max(1L, income.roundToLong() - bountyTotal)
        return WavePlan(w, spawns, waveGold, ehp, duration, costToKill, ratio, hpMult, speed)
    }

    /** Gold for clearing [wave] (base, before prestige multiplier). */
    fun clearBonus(wave: Int): Long = plan(wave).waveGold
}
