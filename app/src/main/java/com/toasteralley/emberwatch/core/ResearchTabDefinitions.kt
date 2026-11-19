package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.pow

/**
 * The "Research" tab (Mastery research). Separate from the Research Bay (which keeps Light Speed Shots
 * and its other projects unchanged). It unlocks only once EVERY Assault, Shield and Ledger workshop
 * upgrade is at its max PERMANENT level, so it is an endgame sink:
 *  - costs Bounty up front (no cores), escalating per level, finite at every level
 *  - real wall-clock durations (hours -> days), persisted, keep running while the app is closed
 *  - its own 2 slots (independent of Research Bay slots)
 * "Mastery" naming keeps it distinct from Research Bay's "Hull Integrity Research".
 */
enum class ResearchCategory(val label: String) {
    ASSAULT("Assault Research"), SHIELD("Shield Research"), LEDGER("Ledger Research")
}

enum class Mastery(
    val category: ResearchCategory,
    val label: String,
    val description: String,
    val maxLevel: Int,
    /** Effect per level (see [effect] for units). */
    val perLevel: Double,
    val bountyBase: Double, val bountyGrowth: Double,
    val baseHours: Double, val hoursGrowth: Double,
) {
    // ---------------------------------------------------------------- Assault Research
    DAMAGE_MASTERY(ResearchCategory.ASSAULT, "Damage Mastery",
        "+2% to how much each Damage level adds (per mastery level)", 50, 0.02, 1e15, 1.45, 4.0, 1.10),
    ATTACK_SPEED_MASTERY(ResearchCategory.ASSAULT, "Attack Speed Mastery",
        "+1% fire rate per level (tower ceiling 60/s still applies)", 30, 0.01, 8e14, 1.45, 4.0, 1.12),
    CRIT_CHANCE_MASTERY(ResearchCategory.ASSAULT, "Crit Chance Mastery",
        "+0.5% crit chance per level", 20, 0.005, 8e14, 1.50, 6.0, 1.12),
    CRIT_FACTOR_MASTERY(ResearchCategory.ASSAULT, "Crit Factor Mastery",
        "+2% critical bonus per level", 50, 0.02, 8e14, 1.45, 4.0, 1.10),
    SUPER_CRIT_MASTERY(ResearchCategory.ASSAULT, "Super Crit Mastery",
        "+2% super crit bonus per level (deeper super crits)", 50, 0.02, 2e15, 1.45, 6.0, 1.10),

    // ---------------------------------------------------------------- Shield Research
    HULL_INTEGRITY_MASTERY(ResearchCategory.SHIELD, "Hull Integrity Mastery",
        "+2% to how much each Hull Integrity level adds (per mastery level)", 50, 0.02, 1e15, 1.45, 4.0, 1.10),
    CORE_REPAIR_MASTERY(ResearchCategory.SHIELD, "Core Repair Mastery",
        "+2% Core Repair per level (2.8% max health/s ceiling still applies)", 50, 0.02, 8e14, 1.45, 4.0, 1.10),
    BARRIER_MASTERY(ResearchCategory.SHIELD, "Barrier Mastery",
        "+0.2% Barrier per level, past the 75% workshop cap (hard cap 90%)", 25, 0.002, 1e15, 1.50, 6.0, 1.12),
    PLATING_MASTERY(ResearchCategory.SHIELD, "Plating Mastery",
        "+2% Plating per level", 50, 0.02, 8e14, 1.45, 4.0, 1.10),

    // ---------------------------------------------------------------- Ledger Research
    BOUNTY_GAIN_MASTERY(ResearchCategory.LEDGER, "Bounty Gain Mastery",
        "+3% ALL Bounty income per level (multiplies Bounty Gain)", 50, 0.03, 1.5e15, 1.45, 6.0, 1.10),
    TRIBUTE_MASTERY(ResearchCategory.LEDGER, "Tribute Mastery",
        "+3% ALL cash income per level (multiplies Tribute)", 50, 0.03, 1e15, 1.45, 4.0, 1.10),
    INTEREST_MASTERY(ResearchCategory.LEDGER, "Interest Per Wave Mastery",
        "+0.1% interest per level past the 10% cap, and +5% interest ceiling per level", 20, 0.001, 1e15, 1.50, 6.0, 1.12),
    ENEMY_DEGRADE_MASTERY(ResearchCategory.LEDGER, "Enemy Degrade Mastery",
        "Degraded enemies can drop +1 more wave per level", 10, 1.0, 3e15, 1.70, 12.0, 1.15);

    /** Bounty to research level [level] -> [level]+1. Finite for every level <= maxLevel. */
    fun bountyCost(level: Int): Double = floor(bountyBase * bountyGrowth.pow(level))

    /** Wall-clock duration of level [level] -> [level]+1: hours, growing to days, capped at 14 days. */
    fun durationMs(level: Int): Long =
        (baseHours * hoursGrowth.pow(level) * HOUR_MS).toLong().coerceAtMost(MAX_DURATION_MS)

    /** Total effect at [level]: fraction for %-style masteries, waves for ENEMY_DEGRADE_MASTERY. */
    fun effect(level: Int): Double = perLevel * level.coerceIn(0, maxLevel)

    fun format(level: Int): String = when (this) {
        ENEMY_DEGRADE_MASTERY -> "+${effect(level).toInt()} waves"
        CRIT_CHANCE_MASTERY, BARRIER_MASTERY, INTEREST_MASTERY -> "+%.1f%%".format(effect(level) * 100)
        else -> "+%.0f%%".format(effect(level) * 100)
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val MAX_DURATION_MS = 14 * 24 * HOUR_MS
        fun of(c: ResearchCategory) = entries.filter { it.category == c }
    }
}

/** One running Mastery research. Times are wall-clock epoch millis (survive app close). */
data class ActiveMastery(val mastery: Mastery, val targetLevel: Int, val startMs: Long, val endMs: Long)

/** Research tab logic over a [SaveData]. Pure Kotlin; persistence is the caller's job. */
class MasteryLab(private val save: SaveData, private val clock: Clock = SystemClock) {
    companion object {
        const val SLOTS = 2

        fun maxedWorkshopCount(save: SaveData) = Upgrade.entries.count { save.level(it) >= it.maxLevel }
        val TOTAL_WORKSHOP = Upgrade.entries.size

        /** Unlocked when every Assault, Shield and Ledger upgrade is at its max permanent level. */
        fun isUnlocked(save: SaveData, debugUnlocked: Boolean = false) =
            debugUnlocked || maxedWorkshopCount(save) == TOTAL_WORKSHOP

        fun lockedText(save: SaveData) = "Max all Workshop upgrades: ${maxedWorkshopCount(save)}/$TOTAL_WORKSHOP"
    }

    sealed interface StartResult {
        data object Ok : StartResult
        data class Blocked(val reason: String) : StartResult
    }

    fun isActive(m: Mastery) = save.activeMastery.any { it.mastery == m }

    fun check(m: Mastery, debugUnlocked: Boolean = false): StartResult {
        val lvl = save.level(m)
        return when {
            !isUnlocked(save, debugUnlocked) -> StartResult.Blocked(lockedText(save))
            lvl >= m.maxLevel -> StartResult.Blocked("Maxed")
            isActive(m) -> StartResult.Blocked("In progress")
            save.activeMastery.size >= SLOTS -> StartResult.Blocked("All slots busy")
            save.bounty < m.bountyCost(lvl) -> StartResult.Blocked("Need Bounty")
            else -> StartResult.Ok
        }
    }

    fun start(m: Mastery, debugUnlocked: Boolean = false): StartResult {
        val r = check(m, debugUnlocked)
        if (r != StartResult.Ok) return r
        val lvl = save.level(m)
        save.bounty -= m.bountyCost(lvl)
        val now = clock.now()
        save.activeMastery += ActiveMastery(m, lvl + 1, now, now + m.durationMs(lvl))
        return r
    }

    fun remainingMs(a: ActiveMastery) = (a.endMs - clock.now()).coerceAtLeast(0)

    /** Completes every finished timer (wall clock). Returns the masteries completed. */
    fun tick(): List<Mastery> {
        val now = clock.now()
        val done = save.activeMastery.filter { it.endMs <= now }
        done.forEach { finish(it) }
        return done.map { it.mastery }
    }

    /** Management override: finish all running Mastery research now. */
    fun completeAllNow(): List<Mastery> {
        val all = save.activeMastery.toList()
        all.forEach { finish(it) }
        return all.map { it.mastery }
    }

    private fun finish(a: ActiveMastery) {
        save.activeMastery.remove(a)
        save.masteries[a.mastery.name] = maxOf(save.level(a.mastery), a.targetLevel).coerceAtMost(a.mastery.maxLevel)
    }
}
