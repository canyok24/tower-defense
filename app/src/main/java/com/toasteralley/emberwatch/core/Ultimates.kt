package com.toasteralley.emberwatch.core

import kotlin.math.pow
import kotlin.math.roundToLong

/** Display names kept in one place so a rename is a one-line change. */
object UltimateNames {
    const val LIGHTHOUSE = "Lighthouse"
    const val TAB = "Ultimates"
}

/**
 * One upgradable Ultimate Weapon stat: value goes from [from] (level 0) to [to] (level [maxLevel]),
 * linearly or geometrically (damage multipliers). Stone cost of level L -> L+1 = costBase x costGrowth^L.
 */
data class UwStat(
    val label: String,
    val maxLevel: Int,
    val from: Double,
    val to: Double,
    val geometric: Boolean = false,
    val unit: String = "",
    val costBase: Double = 10.0,
    val costGrowth: Double = 1.16,
) {
    fun value(level: Int): Double {
        val t = level.coerceIn(0, maxLevel).toDouble() / maxLevel
        return if (geometric) from * (to / from).pow(t) else from + (to - from) * t
    }
    fun cost(level: Int): Long = (costBase * costGrowth.pow(level)).roundToLong()
    fun format(level: Int): String {
        val v = value(level)
        return when (unit) {
            "x" -> "x" + NumberFormat.compact(v)
            "s" -> "%.1fs".format(v)
            "%" -> "%.0f%%".format(v * 100)
            "deg" -> "%.0f°".format(v)
            "r" -> "%.2f".format(v)
            else -> "%.0f".format(v)
        }
    }
}

/**
 * The nine Ultimate Weapons (Stones). Index 0..2 of [stats] are the three upgradable stats.
 * Damage multipliers are x tower damage (Enhancement already multiplies tower damage, so it multiplies UW damage too).
 */
enum class Ultimate(val label: String, val description: String, val stats: List<UwStat>, val plusLabel: String, val plusDescription: String) {
    GOLDEN_TOWER("Golden Tower", "Temporary Bounty and cash multiplier",
        listOf(UwStat("Multiplier", 30, 2.0, 15.0, unit = "x", costBase = 10.0),
            UwStat("Duration", 35, 10.0, 45.0, unit = "s", costBase = 8.0),
            UwStat("Cooldown", 20, 300.0, 100.0, unit = "s", costBase = 12.0, costGrowth = 1.2)),
        "Golden Tower+", "Every kill during the effect also pays +100% of its base Bounty"),
    BLACK_HOLE("Black Hole", "Pulls enemies into a point and crushes them (x5 tower damage per second)",
        listOf(UwStat("Size", 23, 0.12, 0.35, unit = "r", costBase = 10.0),
            UwStat("Duration", 14, 6.0, 20.0, unit = "s", costBase = 10.0, costGrowth = 1.2),
            UwStat("Cooldown", 30, 200.0, 50.0, unit = "s", costBase = 12.0)),
        "Black Hole+", "Crushing damage x5 and bosses are pulled in too"),
    DEATH_WAVE("Death Wave", "A wave sweeps up the lane; each kill stores 25% of the victim's max HP into the wave",
        listOf(UwStat("Damage", 40, 3.0, 12_000.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.14),
            UwStat("Quantity", 4, 1.0, 5.0, costBase = 60.0, costGrowth = 2.0),
            UwStat("Cooldown", 18, 300.0, 120.0, unit = "s", costBase = 12.0, costGrowth = 1.22)),
        "Death Wave+", "Stored damage per kill x3"),
    LIGHTHOUSE(UltimateNames.LIGHTHOUSE, "Rotating beams of light: every lit enemy takes a hit of tower damage × the beam multiplier every 0.5 s (continuous)",
        listOf(UwStat("Damage", 30, 1.5, 100.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.17),
            UwStat("Angle", 33, 30.0, 360.0, unit = "deg", costBase = 8.0, costGrowth = 1.16),
            UwStat("Quantity", 3, 1.0, 4.0, costBase = 100.0, costGrowth = 2.5)),
        "${UltimateNames.LIGHTHOUSE}+", "Beams rotate twice as fast and each beam's multiplier is +25%"),
    CHRONO_FIELD("Chrono Field", "Slow field around the tower (radius 0.6)",
        listOf(UwStat("Duration", 15, 5.0, 20.0, unit = "s", costBase = 8.0, costGrowth = 1.2),
            UwStat("Slow", 25, 0.20, 0.70, unit = "%", costBase = 10.0),
            UwStat("Cooldown", 24, 180.0, 60.0, unit = "s", costBase = 10.0)),
        "Chrono Field+", "Enemies inside the field take +50% damage"),
    CHAIN_LIGHTNING("Chain Lightning", "Tower hits have a chance to arc lightning through nearby enemies",
        listOf(UwStat("Damage", 40, 4.0, 6_000.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.14),
            UwStat("Quantity", 6, 2.0, 8.0, costBase = 40.0, costGrowth = 1.6),
            UwStat("Chance", 25, 0.05, 0.30, unit = "%", costBase = 12.0)),
        "Chain Lightning+", "+10% chance and chain damage x2"),
    SMART_MISSILES("Smart Missiles", "Volley of homing missiles",
        listOf(UwStat("Damage", 40, 8.0, 8_000.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.14),
            UwStat("Quantity", 10, 2.0, 12.0, costBase = 20.0, costGrowth = 1.35),
            UwStat("Cooldown", 22, 30.0, 8.0, unit = "s", costBase = 10.0)),
        "Smart Missiles+", "Missiles explode, splashing full damage in a small radius"),
    INNER_LAND_MINES("Inner Land Mines", "Ring of AoE mines close around the tower",
        listOf(UwStat("Damage", 40, 8.0, 8_000.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.14),
            UwStat("Quantity", 6, 3.0, 9.0, costBase = 30.0, costGrowth = 1.5),
            UwStat("Cooldown", 30, 40.0, 10.0, unit = "s", costBase = 10.0)),
        "Inner Land Mines+", "Each mine re-arms once after exploding"),
    POISON_SWAMP("Poison Swamp", "Damage-over-time zone on the front line (ticks every 0.5 s, radius 0.18)",
        listOf(UwStat("Damage", 40, 8.0, 8_000.0, geometric = true, unit = "x", costBase = 10.0, costGrowth = 1.14),
            UwStat("Duration", 15, 5.0, 20.0, unit = "s", costBase = 10.0, costGrowth = 1.2),
            UwStat("Cooldown", 20, 120.0, 40.0, unit = "s", costBase = 10.0, costGrowth = 1.2)),
        "Poison Swamp+", "Enemies in the swamp are also slowed by 30%");

    fun stat(i: Int) = stats[i]

    companion object {
        /** Unlock cost by SLOT ORDER (the nth UW you unlock costs UNLOCK_COSTS[n-1]); sums to 9,705. */
        val UNLOCK_COSTS = longArrayOf(5, 50, 150, 300, 600, 1000, 1700, 2900, 3000)
        /** Ultimate Weapon Plus cost by how many Plus abilities you already own (any order). */
        val PLUS_COSTS = longArrayOf(500, 750, 1000, 1500, 2000, 2500, 3000, 4000, 5000)
        const val TICK_SECONDS = 0.5f
    }
}

/** Stones-side Ultimate Weapon logic over a [SaveData]. */
object Ultimates {
    fun isUnlocked(save: SaveData, u: Ultimate) = u.name in save.uwUnlockOrder
    fun unlockedCount(save: SaveData) = save.uwUnlockOrder.size
    /** Stones for the next unlock (by slot order), or null when all nine are unlocked. */
    fun nextUnlockCost(save: SaveData): Long? = Ultimate.UNLOCK_COSTS.getOrNull(unlockedCount(save))

    fun unlock(save: SaveData, u: Ultimate): Boolean {
        if (isUnlocked(save, u)) return false
        val c = nextUnlockCost(save) ?: return false
        if (save.stones < c) return false
        save.stones -= c
        save.uwUnlockOrder += u.name
        return true
    }

    fun level(save: SaveData, u: Ultimate, stat: Int) = save.uwStats["${u.name}.$stat"] ?: 0
    fun value(save: SaveData, u: Ultimate, stat: Int) = u.stat(stat).value(level(save, u, stat))

    fun canUpgrade(save: SaveData, u: Ultimate, stat: Int): Boolean {
        if (!isUnlocked(save, u)) return false
        val l = level(save, u, stat)
        return l < u.stat(stat).maxLevel && save.stones >= u.stat(stat).cost(l)
    }

    fun upgrade(save: SaveData, u: Ultimate, stat: Int): Boolean {
        if (!canUpgrade(save, u, stat)) return false
        val l = level(save, u, stat)
        save.stones -= u.stat(stat).cost(l)
        save.uwStats["${u.name}.$stat"] = l + 1
        return true
    }

    /** Plus abilities unlock after all nine UWs are unlocked. */
    fun plusAvailable(save: SaveData) = unlockedCount(save) >= Ultimate.entries.size
    fun hasPlus(save: SaveData, u: Ultimate) = u.name in save.uwPlus
    fun nextPlusCost(save: SaveData): Long? = Ultimate.PLUS_COSTS.getOrNull(save.uwPlus.size)

    fun buyPlus(save: SaveData, u: Ultimate): Boolean {
        if (!plusAvailable(save) || hasPlus(save, u)) return false
        val c = nextPlusCost(save) ?: return false
        if (save.stones < c) return false
        save.stones -= c
        save.uwPlus += u.name
        return true
    }

    /**
     * Lighthouse stacking. [covering] = multiplier of every beam currently lighting the enemy.
     * Normally beams are independent: overlapping beams ADD their multipliers (one beam = its multiplier,
     * no beam = x1). With ALL FOUR beams and the angle maxed (360 deg = every beam covers the whole map),
     * the four multipliers stack MULTIPLICATIVELY (e.g. 100^4 = 1e8). Result is finite (clamped).
     */
    fun lighthouseMultiplier(covering: List<Double>, fullCoverageAllFour: Boolean): Double {
        if (covering.isEmpty()) return 1.0
        val r = if (fullCoverageAllFour) covering.fold(1.0) { a, m -> a * m } else covering.sum()
        return if (r.isNaN()) 1.0 else r.coerceIn(1.0, MAX_MULT)
    }
    const val MAX_MULT = 1e300
}

/** Per-run Ultimate Weapon setup, read from the save once at run start. */
data class UltimateLoadout(
    val unlocked: Set<Ultimate>,
    val values: Map<Ultimate, DoubleArray>,
    val plus: Set<Ultimate>,
) {
    fun has(u: Ultimate) = u in unlocked
    fun v(u: Ultimate, i: Int) = values[u]?.get(i) ?: u.stat(i).value(0)
    fun plus(u: Ultimate) = u in plus

    companion object {
        val NONE = UltimateLoadout(emptySet(), emptyMap(), emptySet())
        fun from(save: SaveData) = UltimateLoadout(
            Ultimate.entries.filter { Ultimates.isUnlocked(save, it) }.toSet(),
            Ultimate.entries.associateWith { u -> DoubleArray(3) { Ultimates.value(save, u, it) } },
            Ultimate.entries.filter { Ultimates.hasPlus(save, it) }.toSet(),
        )
        /** Everything unlocked at the given stat levels (tests / god-mode sims). */
        fun all(level: Int, plus: Boolean = false) = UltimateLoadout(
            Ultimate.entries.toSet(),
            Ultimate.entries.associateWith { u -> DoubleArray(3) { u.stat(it).value(level) } },
            if (plus) Ultimate.entries.toSet() else emptySet(),
        )
    }
}

/** Consumable Stone packs (Play Billing, consumed via consumeAsync after granting). */
data class StonePack(val id: String, val title: String, val stones: Long, val listPrice: String)

object StonePacks {
    val SMALL = StonePack("stones_small", "Handful of Stones", 50, "$0.99")
    val MEDIUM = StonePack("stones_medium", "Pouch of Stones", 300, "$4.99")
    val LARGE = StonePack("stones_large", "Chest of Stones", 1500, "$19.99")
    val ALL = listOf(SMALL, MEDIUM, LARGE)
    val IDS = ALL.map { it.id }
    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /**
     * Grants the Stones for one purchase token exactly once (idempotent: replays, restarts and the
     * pending -> purchased transition can deliver the same token more than once). Returns Stones granted.
     */
    fun grant(save: SaveData, purchaseToken: String, productIds: List<String>): Long {
        if (purchaseToken.isEmpty() || purchaseToken in save.processedStoneTokens) return 0
        val stones = productIds.sumOf { byId(it)?.stones ?: 0L }
        if (stones <= 0) return 0
        save.processedStoneTokens += purchaseToken
        while (save.processedStoneTokens.size > MAX_TOKENS) save.processedStoneTokens.removeAt(0)
        save.stones += stones
        return stones
    }
    const val MAX_TOKENS = 500
}
