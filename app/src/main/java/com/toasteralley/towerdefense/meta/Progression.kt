package com.toasteralley.towerdefense.meta

import com.toasteralley.towerdefense.game.RunModifiers
import com.toasteralley.towerdefense.game.RunResult
import com.toasteralley.towerdefense.game.UpgradeCosts
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Permanent global upgrades bought with Tokens on the Upgrades screen.
 * Value at level L = L x [perLevel]. Cost of buying level L+1 =
 * niceRound([baseCost] x [costGrowth]^L).
 */
enum class MetaUpgrade(
    val displayName: String,
    val maxLevel: Int,
    val perLevel: Float,
    val baseCost: Int,
    val costGrowth: Double,
    val description: String,
) {
    DAMAGE("Damage", 14, 0.025f, 20, 1.30, "+2.5% damage for all towers per level"),
    ATTACK_SPEED("Attack Speed", 10, 0.025f, 25, 1.32, "+2.5% fire rate for all towers per level"),
    RANGE("Range", 10, 0.02f, 15, 1.30, "+2% range for all towers per level"),
    STARTING_GOLD("Starting Gold", 12, 15f, 10, 1.28, "+15 gold at the start of each run per level"),
    HEALTH("Health", 10, 1f, 15, 1.30, "+1 starting life per level"),
    EFFECT_POWER("Effect Power", 10, 0.03f, 15, 1.30, "+3% slow/stun duration and splash radius per level"),
    GOLD_PER_KILL("Gold per Kill", 10, 0.02f, 25, 1.32, "+2% gold from kills per level (wave rewards unchanged)");

    /** True for percentage bonuses, false for flat (gold, lives). */
    val isPercent: Boolean get() = this != STARTING_GOLD && this != HEALTH

    fun valueAt(level: Int): Float = level.coerceIn(0, maxLevel) * perLevel

    /** Tokens needed to go from [level] to level + 1. */
    fun costFrom(level: Int): Int = UpgradeCosts.niceRound(baseCost * costGrowth.pow(level))

    fun format(level: Int): String {
        val v = valueAt(level)
        return if (isPercent) "+%.1f%%".format(v * 100f) else "+${v.toInt()}"
    }
}

/**
 * Meta progression ("Tokens") and prestige ("Super Tokens"), persisted in a
 * [KeyValueStore]. Only the keys listed in this class are ever written, so
 * purchases/entitlements and settings (which may live in the same or another
 * store) are never touched by a prestige reset.
 *
 * Super Tokens (prestige currency, never sold):
 *  - lifetime gold G = all gold income across every run ever played
 *  - total earned  = floor(sqrt(G / [SUPER_TOKEN_GOLD_K]))  -> k-th token needs K*k^2 gold
 *  - available     = total earned - total spent
 *  - prestige multiplier = 1 + 0.1 * totalSpent^0.8 (damage of every tower)
 * Prestiging wipes Tokens, meta upgrades, best wave and run stats. The
 * lifetime gold counter is not zeroed (that would let the cheap first tokens
 * be earned again); instead spent tokens are subtracted, so each Super Token
 * can only ever be earned once and progress continues toward the next one.
 */
class Progression(private val store: KeyValueStore) {

    @get:Synchronized var tokens: Int
        get() = store.getInt(K_TOKENS, 0)
        private set(v) = store.putInt(K_TOKENS, v)

    @get:Synchronized var bestWave: Int
        get() = store.getInt(K_BEST_WAVE, 0)
        private set(v) = store.putInt(K_BEST_WAVE, v)

    val runsPlayed: Int get() = store.getInt(K_RUNS, 0)
    val totalKills: Long get() = store.getLong(K_KILLS, 0)
    val totalBossKills: Long get() = store.getLong(K_BOSS_KILLS, 0)
    val totalTokensEarned: Long get() = store.getLong(K_TOKENS_EARNED, 0)
    /** Gold earned since the last prestige (stat only). */
    val cycleGold: Long get() = store.getLong(K_CYCLE_GOLD, 0)

    /** All-time gold income across all runs (drives Super Token accrual). */
    val lifetimeGold: Long get() = store.getLong(K_LIFETIME_GOLD, 0)
    val superTokensSpent: Int get() = store.getInt(K_SUPER_SPENT, 0)
    val prestigeCount: Int get() = store.getInt(K_PRESTIGES, 0)

    val superTokensEarned: Int get() = superTokensForGold(lifetimeGold)
    val superTokensAvailable: Int get() = (superTokensEarned - superTokensSpent).coerceAtLeast(0)
    val prestigeMultiplier: Float get() = prestigeMultiplierFor(superTokensSpent)

    /** Lifetime gold needed for the next Super Token. */
    val goldForNextSuperToken: Long get() = goldForSuperTokens(superTokensEarned + 1)
    /** 0..1 progress from the last earned Super Token to the next. */
    val superTokenProgress: Float
        get() {
            val lo = goldForSuperTokens(superTokensEarned)
            val hi = goldForNextSuperToken
            return ((lifetimeGold - lo).toFloat() / (hi - lo).toFloat()).coerceIn(0f, 1f)
        }

    fun level(u: MetaUpgrade): Int = store.getInt(key(u), 0).coerceIn(0, u.maxLevel)
    fun isMaxed(u: MetaUpgrade) = level(u) >= u.maxLevel
    /** Cost of the next level, or -1 if maxed. */
    fun nextCost(u: MetaUpgrade): Int = if (isMaxed(u)) -1 else u.costFrom(level(u))

    @Synchronized
    fun buy(u: MetaUpgrade): Boolean {
        if (isMaxed(u)) return false
        val c = nextCost(u)
        if (tokens < c) return false
        tokens -= c
        store.putInt(key(u), level(u) + 1)
        return true
    }

    /** Persists a finished run: Tokens, lifetime gold, stats and best wave. */
    @Synchronized
    fun recordRun(r: RunResult) {
        tokens += r.tokens
        store.putLong(K_TOKENS_EARNED, totalTokensEarned + r.tokens)
        store.putLong(K_LIFETIME_GOLD, lifetimeGold + r.goldEarned)
        store.putLong(K_CYCLE_GOLD, cycleGold + r.goldEarned)
        store.putInt(K_RUNS, runsPlayed + 1)
        store.putLong(K_KILLS, totalKills + r.kills)
        store.putLong(K_BOSS_KILLS, totalBossKills + r.bossKills)
        if (r.wavesCleared > bestWave) bestWave = r.wavesCleared
    }

    @Synchronized
    fun resetBestWave() { bestWave = 0 }

    /** Bonuses for the next run: meta upgrades x prestige. */
    fun runModifiers(): RunModifiers = RunModifiers(
        damageMult = 1f + MetaUpgrade.DAMAGE.valueAt(level(MetaUpgrade.DAMAGE)),
        fireRateMult = 1f + MetaUpgrade.ATTACK_SPEED.valueAt(level(MetaUpgrade.ATTACK_SPEED)),
        rangeMult = 1f + MetaUpgrade.RANGE.valueAt(level(MetaUpgrade.RANGE)),
        effectMult = 1f + MetaUpgrade.EFFECT_POWER.valueAt(level(MetaUpgrade.EFFECT_POWER)),
        killGoldMult = 1f + MetaUpgrade.GOLD_PER_KILL.valueAt(level(MetaUpgrade.GOLD_PER_KILL)),
        bonusStartGold = MetaUpgrade.STARTING_GOLD.valueAt(level(MetaUpgrade.STARTING_GOLD)).toInt(),
        bonusLives = MetaUpgrade.HEALTH.valueAt(level(MetaUpgrade.HEALTH)).toInt(),
        prestigeMult = prestigeMultiplier,
    )

    /**
     * Prestige: spend [amount] (default: all) available Super Tokens. Wipes
     * Tokens, all meta upgrade levels, best wave and stats back to baseline;
     * raises the permanent damage multiplier. Returns false if not possible.
     */
    @Synchronized
    fun prestige(amount: Int = superTokensAvailable): Boolean {
        if (amount <= 0 || amount > superTokensAvailable) return false
        store.putInt(K_SUPER_SPENT, superTokensSpent + amount)
        store.putInt(K_PRESTIGES, prestigeCount + 1)
        store.putInt(K_TOKENS, 0)
        MetaUpgrade.entries.forEach { store.putInt(key(it), 0) }
        store.putInt(K_BEST_WAVE, 0)
        store.putInt(K_RUNS, 0)
        store.putLong(K_KILLS, 0)
        store.putLong(K_BOSS_KILLS, 0)
        store.putLong(K_TOKENS_EARNED, 0)
        store.putLong(K_CYCLE_GOLD, 0)
        return true
    }

    companion object {
        /** Gold scale of the Super Token curve. */
        const val SUPER_TOKEN_GOLD_K = 4000L
        const val PRESTIGE_COEFF = 0.1f
        const val PRESTIGE_EXP = 0.8

        // Token award per run (win or lose).
        const val TOKENS_PER_WAVE = 2
        const val KILLS_PER_TOKEN = 20
        const val TOKENS_PER_BOSS = 5
        const val VICTORY_TOKENS = 20

        fun tokensForRun(wavesCleared: Int, kills: Int, bossKills: Int, victory: Boolean): Int =
            wavesCleared * TOKENS_PER_WAVE + kills / KILLS_PER_TOKEN + bossKills * TOKENS_PER_BOSS +
                if (victory) VICTORY_TOKENS else 0

        fun superTokensForGold(gold: Long): Int =
            if (gold <= 0) 0 else floor(sqrt(gold.toDouble() / SUPER_TOKEN_GOLD_K) + 1e-9).toInt()

        /** Lifetime gold at which the [n]-th Super Token is earned: K * n^2. */
        fun goldForSuperTokens(n: Int): Long = SUPER_TOKEN_GOLD_K * n.toLong() * n.toLong()

        fun prestigeMultiplierFor(spent: Int): Float =
            if (spent <= 0) 1f else 1f + PRESTIGE_COEFF * spent.toDouble().pow(PRESTIGE_EXP).toFloat()

        private fun key(u: MetaUpgrade) = "meta_level_${u.name.lowercase()}"
        private const val K_TOKENS = "tokens"
        private const val K_BEST_WAVE = "best_wave"
        private const val K_RUNS = "runs_played"
        private const val K_KILLS = "total_kills"
        private const val K_BOSS_KILLS = "total_boss_kills"
        private const val K_TOKENS_EARNED = "tokens_earned_total"
        private const val K_CYCLE_GOLD = "cycle_gold"
        private const val K_LIFETIME_GOLD = "lifetime_gold"
        private const val K_SUPER_SPENT = "super_tokens_spent"
        private const val K_PRESTIGES = "prestige_count"
    }
}
