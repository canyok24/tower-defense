package com.toasteralley.towerdefense.meta

import com.toasteralley.towerdefense.game.RunModifiers
import com.toasteralley.towerdefense.game.RunResult
import com.toasteralley.towerdefense.game.UpgradeCosts
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
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

/** Permanent prestige upgrades that Super Tokens are allocated to at reset. */
enum class PrestigeUpgrade(val displayName: String, val description: String) {
    DAMAGE("Tower Damage", "Baseline damage multiplier for every tower"),
    KILL_GOLD("Gold per Kill", "Kill bounty multiplier (stacks with the Tokens upgrade)"),
    WAVE_GOLD("Gold per Wave", "End-of-wave gold multiplier");

    /** Multiplier after [spent] Super Tokens in this category. */
    fun multiplier(spent: Int): Float = when (this) {
        DAMAGE -> Progression.prestigeMultiplierFor(spent)
        KILL_GOLD -> Progression.goldMultiplierFor(spent, Progression.KILL_GOLD_CAP)
        WAVE_GOLD -> Progression.goldMultiplierFor(spent, Progression.WAVE_GOLD_CAP)
    }
}

/** How many Super Tokens to put into each [PrestigeUpgrade] at a reset. */
data class PrestigeAllocation(val damage: Int = 0, val killGold: Int = 0, val waveGold: Int = 0) {
    val total: Int get() = damage + killGold + waveGold
    operator fun get(u: PrestigeUpgrade): Int = when (u) {
        PrestigeUpgrade.DAMAGE -> damage
        PrestigeUpgrade.KILL_GOLD -> killGold
        PrestigeUpgrade.WAVE_GOLD -> waveGold
    }
    fun with(u: PrestigeUpgrade, n: Int): PrestigeAllocation = when (u) {
        PrestigeUpgrade.DAMAGE -> copy(damage = n)
        PrestigeUpgrade.KILL_GOLD -> copy(killGold = n)
        PrestigeUpgrade.WAVE_GOLD -> copy(waveGold = n)
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
 *  - at a reset the player splits Super Tokens across three permanent
 *    [PrestigeUpgrade]s, tracked separately:
 *      damage    = 1 + 0.1  * n^0.8                 (uncapped)
 *      kill gold = min(2.5, 1 + 0.05 * n^0.7)
 *      wave gold = min(2.0, 1 + 0.05 * n^0.7)
 * Prestiging wipes Tokens, meta upgrades, best wave and run stats. The
 * lifetime gold counter is not zeroed (that would let the cheap first tokens
 * be earned again); instead spent tokens are subtracted, so each Super Token
 * can only ever be earned once and progress continues toward the next one.
 */
class Progression(private val store: KeyValueStore) {

    init { migrate() }

    /**
     * v1 stored a single "super_tokens_spent" (all damage). Move it into the
     * damage category once; the old key is kept but no longer read.
     */
    private fun migrate() {
        if (store.getBoolean(K_ALLOC_MIGRATED, false)) return
        if (store.contains(K_SUPER_SPENT_LEGACY) && !store.contains(key(PrestigeUpgrade.DAMAGE))) {
            store.putInt(key(PrestigeUpgrade.DAMAGE), store.getInt(K_SUPER_SPENT_LEGACY, 0))
        }
        store.putBoolean(K_ALLOC_MIGRATED, true)
    }

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
    /** Super Tokens spent on [u] across all prestiges. */
    fun spent(u: PrestigeUpgrade): Int = store.getInt(key(u), 0).coerceAtLeast(0)
    val superTokensSpent: Int get() = PrestigeUpgrade.entries.sumOf { spent(it) }
    fun prestigeMultiplier(u: PrestigeUpgrade): Float = u.multiplier(spent(u))
    val prestigeCount: Int get() = store.getInt(K_PRESTIGES, 0)

    val superTokensEarned: Int get() = superTokensForGold(lifetimeGold)
    val superTokensAvailable: Int get() = (superTokensEarned - superTokensSpent).coerceAtLeast(0)
    /** Prestige damage multiplier. */
    val prestigeMultiplier: Float get() = prestigeMultiplier(PrestigeUpgrade.DAMAGE)
    val prestigeKillGoldMultiplier: Float get() = prestigeMultiplier(PrestigeUpgrade.KILL_GOLD)
    val prestigeWaveGoldMultiplier: Float get() = prestigeMultiplier(PrestigeUpgrade.WAVE_GOLD)

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
        store.putLong(K_TOKENS_EARNED, satAdd(totalTokensEarned, r.tokens.toLong()))
        store.putLong(K_LIFETIME_GOLD, satAdd(lifetimeGold, r.goldEarned))
        store.putLong(K_CYCLE_GOLD, satAdd(cycleGold, r.goldEarned))
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
        prestigeKillGoldMult = prestigeKillGoldMultiplier,
        prestigeWaveGoldMult = prestigeWaveGoldMultiplier,
    )

    /** Prestige spending everything (or [amount]) on damage. */
    fun prestige(amount: Int = superTokensAvailable): Boolean = prestige(PrestigeAllocation(damage = amount))

    /**
     * Prestige: spend the Super Tokens in [allocation] (each category tracked
     * separately). Wipes Tokens, all meta upgrade levels, best wave and stats
     * back to baseline; raises the chosen permanent multipliers. Returns false
     * (and changes nothing) if the allocation is empty, negative or exceeds
     * the available Super Tokens.
     */
    @Synchronized
    fun prestige(allocation: PrestigeAllocation): Boolean {
        val a = allocation
        if (a.damage < 0 || a.killGold < 0 || a.waveGold < 0) return false
        if (a.total <= 0 || a.total > superTokensAvailable) return false
        for (u in PrestigeUpgrade.entries) if (a[u] > 0) store.putInt(key(u), spent(u) + a[u])
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
        /** Gold scale of the Super Token curve (sized for the endless-wave economy). */
        const val SUPER_TOKEN_GOLD_K = 100_000L
        const val PRESTIGE_COEFF = 0.1f
        const val PRESTIGE_EXP = 0.8

        // Token award per run (win or lose).
        const val TOKENS_PER_WAVE = 2
        const val KILLS_PER_TOKEN = 20
        const val TOKENS_PER_BOSS = 5
        /** Victory milestone (wave 20) bonus, once per run. */
        const val VICTORY_TOKENS = 20
        // Knees after which each term grows logarithmically (endless runs).
        const val WAVE_KNEE = 20.0
        const val KILL_TOKEN_KNEE = 50.0
        const val BOSS_KNEE = 8.0

        /** x up to [knee], then knee * (1 + ln(x / knee)): continuous, sublinear. */
        fun softCap(x: Double, knee: Double): Double =
            if (x <= knee) x.coerceAtLeast(0.0) else knee * (1.0 + ln(x / knee))

        /**
         * Tokens for a run: 2 x soft(waves, 20) + soft(kills / 20, 50) +
         * 5 x soft(bosses, 8) (+20 if the wave-20 milestone was reached).
         * Identical to the linear formula for a normal 20-wave run; endless
         * runs keep paying more, but logarithmically.
         */
        fun tokensForRun(wavesCleared: Int, kills: Int, bossKills: Int, victory: Boolean): Int {
            val t = TOKENS_PER_WAVE * softCap(wavesCleared.toDouble(), WAVE_KNEE) +
                floor(softCap(kills.toDouble() / KILLS_PER_TOKEN, KILL_TOKEN_KNEE)) +
                TOKENS_PER_BOSS * softCap(bossKills.toDouble(), BOSS_KNEE) +
                if (victory) VICTORY_TOKENS else 0
            return floor(t + 1e-9).toInt()
        }

        const val GOLD_PRESTIGE_COEFF = 0.05
        const val GOLD_PRESTIGE_EXP = 0.7
        const val KILL_GOLD_CAP = 2.5f
        const val WAVE_GOLD_CAP = 2.0f

        /** min(cap, 1 + 0.05 * spent^0.7): slower than damage, capped. */
        fun goldMultiplierFor(spent: Int, cap: Float): Float =
            if (spent <= 0) 1f else min(cap.toDouble(), 1.0 + GOLD_PRESTIGE_COEFF * spent.toDouble().pow(GOLD_PRESTIGE_EXP)).toFloat()

        private fun satAdd(a: Long, b: Long): Long = if (b > 0 && a > Long.MAX_VALUE / 2 - b) Long.MAX_VALUE / 2 else a + b

        fun superTokensForGold(gold: Long): Int =
            if (gold <= 0) 0 else floor(sqrt(gold.toDouble() / SUPER_TOKEN_GOLD_K) + 1e-9).toInt()

        /** Lifetime gold at which the [n]-th Super Token is earned: K * n^2. */
        fun goldForSuperTokens(n: Int): Long = SUPER_TOKEN_GOLD_K * n.toLong() * n.toLong()

        fun prestigeMultiplierFor(spent: Int): Float =
            if (spent <= 0) 1f else 1f + PRESTIGE_COEFF * spent.toDouble().pow(PRESTIGE_EXP).toFloat()

        private fun key(u: MetaUpgrade) = "meta_level_${u.name.lowercase()}"
        private fun key(u: PrestigeUpgrade) = "super_spent_${u.name.lowercase()}"
        private const val K_TOKENS = "tokens"
        private const val K_BEST_WAVE = "best_wave"
        private const val K_RUNS = "runs_played"
        private const val K_KILLS = "total_kills"
        private const val K_BOSS_KILLS = "total_boss_kills"
        private const val K_TOKENS_EARNED = "tokens_earned_total"
        private const val K_CYCLE_GOLD = "cycle_gold"
        private const val K_LIFETIME_GOLD = "lifetime_gold"
        /** v1 single spent counter (all damage); migrated into super_spent_damage. */
        private const val K_SUPER_SPENT_LEGACY = "super_tokens_spent"
        private const val K_ALLOC_MIGRATED = "prestige_alloc_v2"
        private const val K_PRESTIGES = "prestige_count"
    }
}
