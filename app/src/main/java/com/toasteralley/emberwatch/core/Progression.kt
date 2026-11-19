package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.ln
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
     * 100 -> 200 -> 300 -> 400 -> ... toward 100,000 via periodic updates.
     * The formula is defined for any level, so raising the cap needs no migration.
     */
    const val ENHANCEMENT_CAP = 100
    const val MILESTONE_EVERY = 50
    const val MILESTONE_FACTOR = 1.05
    val EXPONENT: Double = ln(50.0 / MILESTONE_FACTOR.pow(20)) / ln(100.0)

    fun base(level: Int): Double =
        if (level <= 10) 1.0 + 0.1 * level else 2.0 * (level / 10.0).pow(EXPONENT)

    fun milestoneBonus(level: Int): Double = MILESTONE_FACTOR.pow(level / MILESTONE_EVERY)

    fun multiplier(level: Int): Double = base(level) * milestoneBonus(level)

    /** Coins to buy level [level] + 1. Very expensive by design. */
    fun cost(level: Int): Double = 25_000.0 * 1.09.pow(level)
}

// =====================================================================  Workshop
enum class WorkshopStat(val label: String, val baseCost: Double, val growth: Double, val maxLevel: Int, val gateWave: Int) {
    HEALTH("Health", 20.0, 1.13, 1000, 0),
    ATTACK("Attack", 25.0, 1.14, 1000, 0),
    DEFENSE("Defense", 40.0, 1.16, 300, 0),
    ATTACK_SPEED("Attack Speed", 30.0, 1.15, 300, 0),
    CRIT_CHANCE("Critical Chance", 60.0, 1.18, 79, Gates.CRIT_WAVE),
    CRIT_FACTOR("Critical Factor", 50.0, 1.16, 500, Gates.CRIT_WAVE);

    /** Exponential cost: base * growth^level. */
    fun cost(level: Int): Double = floor(baseCost * growth.pow(level))

    /** Raw stat at [level] before perks/research/enhancement. */
    fun value(level: Int): Double = when (this) {
        HEALTH -> 100.0 * (1 + 0.2 * level) * 1.01.pow(level)
        ATTACK -> 10.0 * (1 + 0.25 * level) * 1.012.pow(level)
        DEFENSE -> 0.75 * (1 - 0.985.pow(level))          // damage reduction, -> 75%
        ATTACK_SPEED -> (1.0 + 0.04 * level).coerceAtMost(12.0) // shots per second
        CRIT_CHANCE -> 0.01 * level                       // 0..0.79
        CRIT_FACTOR -> 1.5 + 0.08 * level
    }

    fun format(level: Int): String = when (this) {
        DEFENSE, CRIT_CHANCE -> "%.1f%%".format(value(level) * 100)
        ATTACK_SPEED -> "%.2f/s".format(value(level))
        CRIT_FACTOR -> "x%.2f".format(value(level))
        else -> NumberFormat.compact(value(level))
    }
}

// =====================================================================  Diamond perks
enum class DiamondPerk(val label: String, val description: String, val maxLevel: Int) {
    MULT_DAMAGE("Damage Multiplier", "x1.08 damage per level (compounding)", 60),
    MULT_HEALTH("Health Multiplier", "x1.08 health per level (compounding)", 60),
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

// =====================================================================  Gates & tiers
object Gates {
    const val CRIT_WAVE = 5
    const val PERKS_WAVE = 15
    const val ULTIMATES_WAVE = 20
    const val ENHANCEMENT_WAVE = 30
    const val TIER_UNLOCK_WAVE = 50
    fun open(requiredWave: Int, save: SaveData, bypass: Boolean) = bypass || save.bestWave >= requiredWave
}

object Tiers {
    const val MAX_TIER = 10
    fun hpMult(tier: Int) = 3.0.pow(tier - 1)
    fun damageMult(tier: Int) = 2.5.pow(tier - 1)
    fun coinMult(tier: Int) = 2.2.pow(tier - 1)
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

    /** Everything that multiplies coin income: packs x rewarded x research x enhancement x tier. */
    fun total(save: SaveData, owned: Set<String>, nowMs: Long): Double =
        packs(owned) *
            (if (boostActive(save, nowMs)) REWARDED_FACTOR else 1.0) *
            ResearchProject.COIN_BONUS.effect(save.level(ResearchProject.COIN_BONUS)) *
            Enhancement.multiplier(save.enhancement) *
            Tiers.coinMult(save.selectedTier)
}

// =====================================================================  Number format
object NumberFormat {
    private val suffixes = listOf("", "K", "M", "B", "T", "q", "Q", "s", "S", "O", "N", "D")
    fun compact(v: Double): String {
        if (v.isNaN()) return "0"
        if (v < 1000) return if (v < 10 && v != floor(v)) "%.1f".format(v) else "%.0f".format(v)
        var x = v; var i = 0
        while (x >= 1000 && i < suffixes.lastIndex) { x /= 1000; i++ }
        if (x >= 1000) return "%.2e".format(v)
        return (if (x >= 100) "%.0f" else if (x >= 10) "%.1f" else "%.2f").format(x) + suffixes[i]
    }
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${sec}s"; else -> "${sec}s" }
    }
}
