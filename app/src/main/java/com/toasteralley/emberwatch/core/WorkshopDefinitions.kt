package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.pow

/** The three Workshop tabs (also the three tabs of the in-run upgrade panel). */
enum class WorkshopTab(val label: String) { ASSAULT("ASSAULT"), SHIELD("SHIELD"), LEDGER("LEDGER") }

/**
 * THE Workshop data table. Every upgrade has:
 *  - a COIN cost curve for permanent levels (bought between runs): coinBase x coinGrowth^permanentLevel
 *  - a CASH cost curve for in-run levels (reset each run):      cashBase x cashGrowth^runLevel
 *  - one max level shared by both (permanent + in-run <= maxLevel)
 *  - a gate (best wave reached) before it can be bought either way
 *  - effect formulas below, evaluated at the TOTAL level (permanent + in-run)
 *
 * Max levels keep every cost finite (largest: 25 x 1.14^1000 ~ 1e58 coins).
 */
enum class Upgrade(
    val tab: WorkshopTab,
    val label: String,
    val description: String,
    val maxLevel: Int,
    val coinBase: Double, val coinGrowth: Double,
    val cashBase: Double, val cashGrowth: Double,
    val gateWave: Int = 0,
) {
    // ------------------------------------------------------------------ ASSAULT
    DAMAGE(WorkshopTab.ASSAULT, "Damage", "Damage per shot", 1000, 25.0, 1.14, 15.0, 1.16),
    ATTACK_SPEED(WorkshopTab.ASSAULT, "Attack Speed", "Shots per second (cap 12/s before research)", 275, 30.0, 1.15, 20.0, 1.18),
    CRIT_CHANCE(WorkshopTab.ASSAULT, "Crit Chance", "Chance for a critical hit (cap 79%)", 79, 60.0, 1.18, 40.0, 1.20, gateWave = 5),
    CRIT_FACTOR(WorkshopTab.ASSAULT, "Crit Factor", "Critical damage multiplier", 500, 50.0, 1.16, 30.0, 1.18, gateWave = 5),
    RANGE(WorkshopTab.ASSAULT, "Range", "Targeting distance from the tower (cap 2.4)", 60, 40.0, 1.17, 25.0, 1.20),
    MULTISHOT(WorkshopTab.ASSAULT, "Multishot", "Chance to fire at extra targets (cap 50%, up to +3 targets)", 50, 120.0, 1.20, 60.0, 1.22, gateWave = 8),
    RAPID_FIRE(WorkshopTab.ASSAULT, "Rapid Fire", "Chance per shot to start a 2 s burst of boosted fire rate (cap 25%, x3)", 50, 150.0, 1.20, 70.0, 1.22, gateWave = 10),
    BOUNCE_SHOT(WorkshopTab.ASSAULT, "Bounce Shot", "Chance a hit bounces to nearby enemies (cap 50%, up to 3 bounces)", 50, 180.0, 1.20, 80.0, 1.22, gateWave = 12),
    SUPER_CRIT(WorkshopTab.ASSAULT, "Super Crit", "Chance on a crit to multiply again (cap 40%); stacks with the Diamond Perk", 80, 400.0, 1.22, 150.0, 1.24, gateWave = 25),
    REND_ARMOR(WorkshopTab.ASSAULT, "Rend Armor", "Each hit shreds armor: that enemy takes more damage (cap +20%/hit, +200% total)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 20),

    // ------------------------------------------------------------------ SHIELD
    HEALTH(WorkshopTab.SHIELD, "Health", "Tower max health", 1000, 20.0, 1.13, 15.0, 1.15),
    HEALTH_REGEN(WorkshopTab.SHIELD, "Health Regen", "% of max health regenerated per second (cap 4%/s)", 50, 30.0, 1.15, 20.0, 1.18),
    DEFENSE_PCT(WorkshopTab.SHIELD, "Defense %", "Damage reduction (cap 75%)", 300, 40.0, 1.16, 30.0, 1.19),
    DEFENSE_ABS(WorkshopTab.SHIELD, "Defense Absolute", "Flat damage removed from every hit (after Defense %, min 10% of the hit gets through)", 500, 35.0, 1.15, 25.0, 1.18, gateWave = 3),
    THORNS(WorkshopTab.SHIELD, "Thorns", "Reflects % of damage taken back at the attacker (cap 100%)", 50, 80.0, 1.18, 40.0, 1.20, gateWave = 6),
    LIFESTEAL(WorkshopTab.SHIELD, "Lifesteal", "% of damage dealt heals the tower (cap 5%)", 50, 150.0, 1.20, 70.0, 1.22, gateWave = 10),
    KNOCKBACK(WorkshopTab.SHIELD, "Knockback", "Chance a hit pushes the enemy back up (cap 50%; bosses resist 80%)", 50, 60.0, 1.18, 35.0, 1.20, gateWave = 4),
    ORBS(WorkshopTab.SHIELD, "Orbs", "Orbiting projectiles that damage enemies they touch (up to 4)", 60, 300.0, 1.22, 120.0, 1.24, gateWave = 15),
    SHOCKWAVE(WorkshopTab.SHIELD, "Shockwave", "Periodic pulse that damages and pushes back nearby enemies", 80, 300.0, 1.21, 120.0, 1.23, gateWave = 18),
    DEATH_DEFY(WorkshopTab.SHIELD, "Death Defy", "Chance to survive a lethal hit at 1% health (cap 30%)", 60, 500.0, 1.24, 200.0, 1.26, gateWave = 25),
    WALL_HEALTH(WorkshopTab.SHIELD, "Wall Health", "A wall above the tower that blocks contact and absorbs damage first (up to 200% of max health)", 40, 150.0, 1.19, 60.0, 1.21, gateWave = 12),
    WALL_REBUILD(WorkshopTab.SHIELD, "Wall Rebuild", "Wall regenerates and rebuilds faster after it falls (min 8 s)", 55, 150.0, 1.19, 60.0, 1.21, gateWave = 12),
    LAND_MINES(WorkshopTab.SHIELD, "Land Mines", "Periodically lays mines in the lane that explode on contact", 80, 250.0, 1.21, 100.0, 1.23, gateWave = 20),

    // ------------------------------------------------------------------ LEDGER (exactly 9)
    BOUNTY(WorkshopTab.LEDGER, "Bounty", "Multiplies ALL coin income: kills, wave rewards, bosses", 100, 100.0, 1.16, 50.0, 1.20),
    TRIBUTE(WorkshopTab.LEDGER, "Tribute", "Multiplies ALL cash income: kills and wave rewards", 100, 60.0, 1.15, 30.0, 1.18),
    INTEREST(WorkshopTab.LEDGER, "Interest Per Wave", "Cash interest on held cash after each wave (cap 10%, max 40x wave cash)", 50, 200.0, 1.20, 80.0, 1.22, gateWave = 5),
    FREE_ASSAULT(WorkshopTab.LEDGER, "Free Assault", "Chance an in-run ASSAULT purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    FREE_SHIELD(WorkshopTab.LEDGER, "Free Shield", "Chance an in-run SHIELD purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    FREE_LEDGER(WorkshopTab.LEDGER, "Free Ledger", "Chance an in-run LEDGER purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    ENEMY_DEGRADE(WorkshopTab.LEDGER, "Enemy Degrade Chance", "Chance a spawning enemy uses the stats of an earlier wave (cap 50%, 1..6 waves lower)", 50, 1000.0, 1.30, 400.0, 1.32, gateWave = 15),
    RECOVERY_AMOUNT(WorkshopTab.LEDGER, "Recovery Amount", "Heals % of max health at the end of each wave (cap 50%)", 50, 80.0, 1.17, 40.0, 1.20, gateWave = 3),
    MAX_RECOVERY(WorkshopTab.LEDGER, "Max Recovery", "Cap on the per-wave heal, % of max health (10% -> 100%)", 90, 80.0, 1.17, 40.0, 1.20, gateWave = 3);

    fun coinCost(permanentLevel: Int): Double = floor(coinBase * coinGrowth.pow(permanentLevel))
    fun cashCost(runLevel: Int): Double = floor(cashBase * cashGrowth.pow(runLevel))

    /** Primary effect at TOTAL level [l] (units in [format]). */
    fun value(l: Int): Double = when (this) {
        DAMAGE -> 10.0 * (1 + 0.25 * l) * 1.012.pow(l)
        ATTACK_SPEED -> (1.0 + 0.04 * l).coerceAtMost(12.0)
        CRIT_CHANCE -> (0.01 * l).coerceAtMost(0.79)
        CRIT_FACTOR -> 1.5 + 0.08 * l
        RANGE -> (1.2 + 0.02 * l).coerceAtMost(2.4)
        MULTISHOT -> (0.01 * l).coerceAtMost(0.5)
        RAPID_FIRE -> (0.005 * l).coerceAtMost(0.25)
        BOUNCE_SHOT -> (0.01 * l).coerceAtMost(0.5)
        SUPER_CRIT -> (0.005 * l).coerceAtMost(0.4)
        REND_ARMOR -> (0.004 * l).coerceAtMost(0.2)
        HEALTH -> 100.0 * (1 + 0.2 * l) * 1.01.pow(l)
        HEALTH_REGEN -> (0.0008 * l).coerceAtMost(0.04)
        DEFENSE_PCT -> 0.75 * (1 - 0.985.pow(l))
        DEFENSE_ABS -> 1.5 * l * 1.01.pow(l)
        THORNS -> (0.02 * l).coerceAtMost(1.0)
        LIFESTEAL -> (0.001 * l).coerceAtMost(0.05)
        KNOCKBACK -> (0.01 * l).coerceAtMost(0.5)
        ORBS -> orbCount(l).toDouble()
        SHOCKWAVE -> if (l == 0) 0.0 else (12.0 - 0.1 * l).coerceAtLeast(4.0)     // seconds between pulses
        DEATH_DEFY -> (0.005 * l).coerceAtMost(0.3)
        WALL_HEALTH -> (0.05 * l).coerceAtMost(2.0)                                // x tower max health
        WALL_REBUILD -> (30.0 - 0.4 * l).coerceAtLeast(8.0)                        // seconds to rebuild
        LAND_MINES -> if (l == 0) 0.0 else (10.0 - 0.1 * l).coerceAtLeast(2.0)    // seconds between mines
        BOUNTY, TRIBUTE -> 1.0 + 0.03 * l
        INTEREST -> (0.002 * l).coerceAtMost(0.10)
        FREE_ASSAULT, FREE_SHIELD, FREE_LEDGER -> (0.005 * l).coerceAtMost(0.25)
        ENEMY_DEGRADE -> (0.01 * l).coerceAtMost(0.5)
        RECOVERY_AMOUNT -> (0.01 * l).coerceAtMost(0.5)
        MAX_RECOVERY -> (0.10 + 0.01 * l).coerceAtMost(1.0)
    }

    /** Secondary effect where an upgrade has two numbers. */
    fun secondary(l: Int): Double = when (this) {
        MULTISHOT -> (1 + l / 25).coerceAtMost(3).toDouble()          // extra targets
        RAPID_FIRE -> (2.0 + 0.02 * l).coerceAtMost(3.0)              // fire-rate multiplier during burst
        BOUNCE_SHOT -> (1 + l / 20).coerceAtMost(3).toDouble()        // bounces
        SUPER_CRIT -> 1.5 + 0.05 * l                                  // super-crit factor
        REND_ARMOR -> (0.04 * l).coerceAtMost(2.0)                    // max total shred
        KNOCKBACK -> (0.02 + 0.0012 * l).coerceAtMost(0.08)           // push distance
        ORBS -> 0.5 + 0.05 * l                                        // orb damage x attack
        SHOCKWAVE -> 1.0 + 0.2 * l                                    // pulse damage x attack
        WALL_REBUILD -> (0.001 * l).coerceAtMost(0.05)                // wall regen /s
        LAND_MINES -> 2.0 + 0.3 * l                                   // mine damage x attack
        ENEMY_DEGRADE -> (1 + l / 10).toDouble()                      // max waves degraded
        else -> 0.0
    }

    fun format(l: Int): String {
        fun pct(v: Double) = "%.1f%%".format(v * 100)
        return when (this) {
            DAMAGE, HEALTH -> NumberFormat.compact(value(l))
            DEFENSE_ABS -> "-" + NumberFormat.compact(value(l))
            ATTACK_SPEED -> "%.2f/s".format(value(l))
            CRIT_FACTOR, BOUNTY, TRIBUTE -> "x%.2f".format(value(l))
            RANGE -> "%.2f".format(value(l))
            MULTISHOT -> "${pct(value(l))} +${secondary(l).toInt()}"
            RAPID_FIRE -> "${pct(value(l))} x%.2f".format(secondary(l))
            BOUNCE_SHOT -> "${pct(value(l))} x${secondary(l).toInt()}"
            SUPER_CRIT -> "${pct(value(l))} x%.2f".format(secondary(l))
            REND_ARMOR -> "${pct(value(l))}/hit max ${pct(secondary(l))}"
            HEALTH_REGEN -> pct(value(l)) + "/s"
            KNOCKBACK -> pct(value(l))
            ORBS -> "${value(l).toInt()} orbs x%.2f".format(secondary(l))
            SHOCKWAVE -> if (l == 0) "off" else "every %.1fs x%.1f".format(value(l), secondary(l))
            WALL_HEALTH -> if (l == 0) "no wall" else "${pct(value(l))} HP"
            WALL_REBUILD -> "%.0fs, +${pct(secondary(l))}/s".format(value(l))
            LAND_MINES -> if (l == 0) "off" else "every %.1fs x%.1f".format(value(l), secondary(l))
            ENEMY_DEGRADE -> "${pct(value(l))} (1-${secondary(l).toInt()} waves)"
            else -> pct(value(l))
        }
    }

    companion object {
        fun ofTab(t: WorkshopTab) = entries.filter { it.tab == t }
        fun orbCount(l: Int) = ((l + 9) / 10).coerceAtMost(4)
        fun freeUpgradeFor(t: WorkshopTab) = when (t) {
            WorkshopTab.ASSAULT -> FREE_ASSAULT; WorkshopTab.SHIELD -> FREE_SHIELD; WorkshopTab.LEDGER -> FREE_LEDGER
        }
        /** Interest is capped per wave at this many times the wave's base cash value. */
        const val INTEREST_CAP_WAVE_CASH = 40.0
    }
}

/** Permanent workshop purchases (Coins). */
object Workshop {
    fun gateOpen(u: Upgrade, save: SaveData, bypass: Boolean) = Gates.open(u.gateWave, save, bypass)

    fun canBuy(u: Upgrade, save: SaveData, bypass: Boolean): Boolean {
        val l = save.level(u)
        return gateOpen(u, save, bypass) && l < u.maxLevel && save.coins >= u.coinCost(l)
    }

    fun buy(u: Upgrade, save: SaveData, bypass: Boolean): Boolean {
        if (!canBuy(u, save, bypass)) return false
        val l = save.level(u)
        save.coins -= u.coinCost(l)
        save.workshop[u.name] = l + 1
        return true
    }
}
