package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

// =====================================================================  Enhancement
/**
 * Enhancement window: one global multiplier applied on top of every other
 * window (workshop stats, perks, ultimates, coin income).
 *
 * Formula (L = level):
 *   base(L) = 1 + 0.1*L                    for L <= 10   (1.1, 1.2 ... 2.0 at L10)
 *   base(L) = 2 * (L/10)^P                 for L >  10   (continuous at L10)
 *   milestone(L) = 1.05 ^ floor(L/50)      (+5% compounding bonus every 50 levels)
 *   multiplier(L) = base(L) * milestone(L)
 * P is solved so multiplier(1000) == 100 exactly:
 *   2 * 100^P * 1.05^20 = 100  =>  P = ln(50 / 1.05^20) / ln(100) ~= 0.6376
 * Sample values: L10 = 2.00, L20 = 3.11, L50 = 5.86, L100 = 9.57, L500 = 39.5, L1000 = 100.
 */
object Enhancement {
    /**
     * Current max level. Staged releases raise this constant only:
     * 1000 (now) -> 2000 -> 3000 -> ... toward 100,000 via periodic updates.
     * The formula (and EnemyPool thresholds) are defined for any level, so raising
     * the cap needs no migration.
     */
    const val ENHANCEMENT_CAP = 1000
    const val MILESTONE_EVERY = 50
    const val MILESTONE_FACTOR = 1.05
    val EXPONENT: Double = ln(50.0 / MILESTONE_FACTOR.pow(20)) / ln(100.0)

    fun base(level: Int): Double =
        if (level <= 10) 1.0 + 0.1 * level else 2.0 * (level / 10.0).pow(EXPONENT)

    fun milestoneBonus(level: Int): Double = MILESTONE_FACTOR.pow(level / MILESTONE_EVERY)

    fun multiplier(level: Int): Double = base(level) * milestoneBonus(level)

    /**
     * Coins to buy level [level] + 1. Steep but finite:
     *   L <  100: 25,000 x 1.09^L                 (L99 ~ 1.27e8)
     *   L >= 100: cost(100) x 1.035^(L-100)       (L500 ~ 1.3e14, L999 ~ 3.6e21)
     * Beyond the current cap the 1.035 growth continues (L5000 ~ 4e81), still a finite Double.
     */
    fun cost(level: Int): Double =
        if (level < SOFT_KNEE) 25_000.0 * 1.09.pow(level)
        else 25_000.0 * 1.09.pow(SOFT_KNEE) * 1.035.pow(level - SOFT_KNEE)
    const val SOFT_KNEE = 100
}

// =====================================================================  Diamond perks
enum class DiamondPerk(val label: String, val description: String, val maxLevel: Int) {
    MULT_DAMAGE("Damage Multiplier", "x1.08 damage per level (compounding)", 60),
    MULT_HEALTH("Hull Integrity Multiplier", "x1.08 hull integrity (max health) per level (compounding)", 60),
    MULT_CRIT_RATE("Crit Rate Multiplier", "+4% of crit chance per level", 25),
    CRIT_FACTOR("Critical Factor", "+0.15 crit factor per level", 50),
    ADV_CRIT_RATE("Advanced Crit Rate", "+0.5% flat crit chance per level", 40),
    ADV_CRIT_FACTOR("Advanced Crit Factor", "x1.05 crit factor per level (compounding)", 40),
    SUPER_CRITS("Super Crits", "Crit-on-crit: +1% chance & +0.25 super factor per level", 50);

    /** Diamonds to buy level [level] + 1. */
    fun cost(level: Int): Long = (5 + 3 * level + 0.15 * level * level).toLong()
}

// =====================================================================  Ultimate weapons
enum class UltimateWeapon(val label: String, val description: String, val maxLevel: Int, val baseCooldown: Float, val minCooldown: Float) {
    EMBER_NOVA("Ember Nova", "Burst of embers damaging every mob near the wall", 30, 60f, 20f),
    FROST_LATTICE("Frost Lattice", "Slows every mob on the field", 30, 70f, 25f),
    CHAIN_ARC("Chain Arc", "Lightning that chains through several mobs", 30, 25f, 8f),
    GRAVITY_WELL("Gravity Well", "Drags mobs back toward the top and crushes them", 30, 90f, 30f);

    /** Diamonds: unlock (level 0 -> 1) costs 20, then upgrades. */
    fun cost(level: Int): Long = if (level == 0) 20 else (8 + 6 * level).toLong()
    fun cooldown(level: Int): Float = (baseCooldown * 0.96f.pow(level - 1)).coerceAtLeast(minCooldown)
    /** Damage as a multiple of tower attack. */
    fun damageFactor(level: Int): Double = when (this) {
        EMBER_NOVA -> 5.0 + 3.0 * level
        FROST_LATTICE -> 0.0
        CHAIN_ARC -> 3.0 + 2.0 * level
        GRAVITY_WELL -> 2.0 + 1.5 * level
    }
    fun slow(level: Int) = (0.3f + 0.02f * level).coerceAtMost(0.7f)
    fun duration(level: Int) = 5f + 0.5f * level
    fun chainTargets(level: Int) = 3 + level
}

// =====================================================================  Gates
object Gates {
    const val CRIT_WAVE = 5
    const val PERKS_WAVE = 15
    const val ULTIMATES_WAVE = 20
    const val ENHANCEMENT_WAVE = 30
    fun open(requiredWave: Int, save: SaveData, bypass: Boolean) = bypass || save.bestWave >= requiredWave
}

// =====================================================================  Coin multipliers
object CoinMultipliers {
    const val REWARDED_FACTOR = 1.5
    const val REWARDED_CHUNK_MS = 20L * 60 * 1000
    const val REWARDED_MAX_MS = 2L * 60 * 60 * 1000

    /** Product multipliers from owned packs, stacked multiplicatively. */
    fun packs(owned: Set<String>): Double =
        Products.ALL.filter { it.id in owned }.fold(1.0) { acc, p -> acc * p.coinMultiplier }

    fun boostActive(save: SaveData, nowMs: Long) = save.boostUntilMs > nowMs

    /** Adds one 20-minute chunk; total remaining is capped at 2 hours. */
    fun addRewardedChunk(save: SaveData, nowMs: Long) {
        val start = maxOf(nowMs, save.boostUntilMs)
        save.boostUntilMs = minOf(start + REWARDED_CHUNK_MS, nowMs + REWARDED_MAX_MS)
    }

    fun canAddChunk(save: SaveData, nowMs: Long) = save.boostUntilMs - nowMs <= REWARDED_MAX_MS - 60_000

    /**
     * Everything that multiplies coin income:
     * packs x rewarded x research x enhancement x selected-stage multiplier x stage milestone bonuses.
     */
    fun total(save: SaveData, owned: Set<String>, nowMs: Long): Double =
        packs(owned) *
            (if (boostActive(save, nowMs)) REWARDED_FACTOR else 1.0) *
            ResearchProject.COIN_BONUS.effect(save.level(ResearchProject.COIN_BONUS)) *
            Enhancement.multiplier(save.enhancement) *
            StageDefinitions.get(save.selectedStage).coinMult *
            Stages.coinBonus(save)
}

// =====================================================================  Number format
object NumberFormat {
    private val named = listOf("", "K", "M", "B", "T")

    /** Suffix for 1000^[group]: "", K, M, B, T, then aa, ab, ... az, ba, ... zz (1000^5 .. 1000^680). */
    fun suffix(group: Int): String {
        if (group < named.size) return named[group]
        val i = group - named.size
        if (i >= 26 * 26) return "e${group * 3}"
        return "${'a' + i / 26}${'a' + i % 26}"
    }

    /** 999 -> "999", 1.2e6 -> "1.20M", 3.4e15 -> "3.40aa". Handles any finite Double. */
    fun compact(v: Double): String {
        if (v.isNaN()) return "0"
        if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
        if (v < 0) return "-" + compact(-v)
        if (v < 999.5) return if (v < 10 && v != floor(v)) "%.1f".format(v) else "%.0f".format(v)
        var group = floor(log10(v) / 3).toInt()
        var x = v / 10.0.pow(group * 3)
        if (x >= 999.5) { x /= 1000; group++ }   // "%.0f" would otherwise print "1000"
        return (if (x >= 100) "%.0f" else if (x >= 10) "%.1f" else "%.2f").format(x) + suffix(group)
    }
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${sec}s"; else -> "${sec}s" }
    }
}
