package com.toasteralley.emberwatch.core

import kotlin.math.floor
import kotlin.math.pow

/** The three Workshop tabs (also the three tabs of the in-run upgrade panel). */
enum class WorkshopTab(val label: String) { ASSAULT("ASSAULT"), SHIELD("SHIELD"), LEDGER("LEDGER") }

/**
 * THE Workshop data table. Every upgrade has:
 *  - a BOUNTY cost curve for permanent levels (bought between runs): bountyBase x bountyGrowth^permanentLevel
 *  - a CASH cost curve for in-run levels (reset each run):      cashBase x cashGrowth^runLevel
 *  - one max level shared by both (permanent + in-run <= maxLevel)
 *  - a gate (best wave reached) before it can be bought either way
 *  - effect formulas below, evaluated at the TOTAL level (permanent + in-run)
 *
 * Max levels keep every cost finite. Damage (max 10,000) uses 25 x 1.14^L bounty exactly up to L5000
 * (~1e286), then x1.008 per level (~1e303 at L10000); its cash curve 15 x 1.16^L softens to x1.03
 * from L2500 (1.16^L alone would overflow a double near L4760). Attack Speed softens after L300.
 */
enum class Upgrade(
    val tab: WorkshopTab,
    val label: String,
    val description: String,
    val maxLevel: Int,
    val bountyBase: Double, val bountyGrowth: Double,
    val cashBase: Double, val cashGrowth: Double,
    val gateWave: Int = 0,
    /** Above these levels the curve continues with a softer growth so costs stay finite to max level. */
    val bountySoftFrom: Int = Int.MAX_VALUE, val bountySoftGrowth: Double = bountyGrowth,
    val cashSoftFrom: Int = Int.MAX_VALUE, val cashSoftGrowth: Double = cashGrowth,
) {
    // ------------------------------------------------------------------ ASSAULT
    DAMAGE(WorkshopTab.ASSAULT, "Damage", "Damage per shot", 10_000, 25.0, 1.14, 15.0, 1.16,
        bountySoftFrom = 5000, bountySoftGrowth = 1.008, cashSoftFrom = 2500, cashSoftGrowth = 1.03),
    ATTACK_SPEED(WorkshopTab.ASSAULT, "Attack Speed", "Shots per second (cap 40/s at L1000)", 1000, 30.0, 1.15, 20.0, 1.18,
        bountySoftFrom = 300, bountySoftGrowth = 1.05, cashSoftFrom = 300, cashSoftGrowth = 1.06),
    CRIT_CHANCE(WorkshopTab.ASSAULT, "Crit Chance", "Chance for a critical hit (cap 90%)", 90, 60.0, 1.18, 40.0, 1.20, gateWave = 5),
    CRIT_FACTOR(WorkshopTab.ASSAULT, "Crit Factor", "Critical damage multiplier", 175, 50.0, 1.16, 30.0, 1.18, gateWave = 5),
    RANGE(WorkshopTab.ASSAULT, "Range", "Targeting distance from the tower (cap 2.4 at L90)", 90, 40.0, 1.17, 25.0, 1.20),
    MULTISHOT(WorkshopTab.ASSAULT, "Multishot", "Chance to fire at extra targets (cap 50%, up to +3 targets)", 110, 120.0, 1.20, 60.0, 1.22, gateWave = 8),
    RAPID_FIRE(WorkshopTab.ASSAULT, "Rapid Fire", "Chance per shot to start a 2 s burst of boosted fire rate (cap 25%, x3)", 60, 150.0, 1.20, 70.0, 1.22, gateWave = 10),
    BOUNCE_SHOT(WorkshopTab.ASSAULT, "Bounce Shot", "Chance a hit bounces to nearby enemies (cap 50%, up to 3 bounces)", 60, 180.0, 1.20, 80.0, 1.22, gateWave = 12),
    SUPER_CRIT(WorkshopTab.ASSAULT, "Super Crit", "Chance on a crit to multiply again (cap 40%); stacks with the Diamond Perk", 95, 400.0, 1.22, 150.0, 1.24, gateWave = 25),
    REND_ARMOR(WorkshopTab.ASSAULT, "Rend Armor", "Each hit shreds armor: that enemy takes more damage (cap +20%/hit, +200% total)", 60, 250.0, 1.21, 100.0, 1.23, gateWave = 20),

    // ------------------------------------------------------------------ SHIELD
    HULL_INTEGRITY(WorkshopTab.SHIELD, "Hull Integrity", "Tower max health", 10_000, 20.0, 1.13, 15.0, 1.15,
        bountySoftFrom = 5000, bountySoftGrowth = 1.008, cashSoftFrom = 2500, cashSoftGrowth = 1.03),
    CORE_REPAIR(WorkshopTab.SHIELD, "Core Repair", "Restores hull per second: 1% of the Hull Integrity value at the same level (ceiling 2.8% of max health/s)", 10_000, 30.0, 1.15, 20.0, 1.18,
        bountySoftFrom = 4500, bountySoftGrowth = 1.008, cashSoftFrom = 2500, cashSoftGrowth = 1.03),
    BARRIER(WorkshopTab.SHIELD, "Barrier", "Damage reduction (cap 75%)", 300, 40.0, 1.16, 30.0, 1.19),
    PLATING(WorkshopTab.SHIELD, "Plating", "Flat damage removed from every hit (after Barrier, min 10% of the hit gets through)", 10_000, 35.0, 1.15, 25.0, 1.18, gateWave = 3,
        bountySoftFrom = 4500, bountySoftGrowth = 1.008, cashSoftFrom = 2500, cashSoftGrowth = 1.03),
    COUNTERFIRE(WorkshopTab.SHIELD, "Counterfire", "Reflects % of damage taken back at the attacker (cap 100%)", 50, 80.0, 1.18, 40.0, 1.20, gateWave = 6),
    DRAIN(WorkshopTab.SHIELD, "Drain", "% of damage dealt heals the tower (cap 5%)", 50, 150.0, 1.20, 70.0, 1.22, gateWave = 10),
    REPULSE(WorkshopTab.SHIELD, "Repulse", "Chance a hit pushes the enemy back up (cap 50%; bosses resist 80%)", 50, 60.0, 1.18, 35.0, 1.20, gateWave = 4),
    SATELLITES(WorkshopTab.SHIELD, "Satellites", "Orbiting projectiles that damage enemies they touch (up to 4)", 60, 300.0, 1.22, 120.0, 1.24, gateWave = 15),
    PULSE(WorkshopTab.SHIELD, "Pulse", "Periodic pulse that damages and pushes back nearby enemies", 80, 300.0, 1.21, 120.0, 1.23, gateWave = 18),
    LAST_STAND(WorkshopTab.SHIELD, "Last Stand", "Chance to survive a lethal hit at 1% health (cap 30%)", 60, 500.0, 1.24, 200.0, 1.26, gateWave = 25),
    FORTIFICATION(WorkshopTab.SHIELD, "Fortification", "A fortification above the tower that blocks contact and absorbs damage first (up to 200% of max health)", 40, 150.0, 1.19, 60.0, 1.21, gateWave = 12),
    RECONSTRUCTION(WorkshopTab.SHIELD, "Reconstruction", "Fortification regenerates, and rebuilds faster after it falls (min 8 s)", 55, 150.0, 1.19, 60.0, 1.21, gateWave = 12),
    CHARGES(WorkshopTab.SHIELD, "Charges", "Periodically lays charges in the lane that explode on contact", 80, 250.0, 1.21, 100.0, 1.23, gateWave = 20),

    // ------------------------------------------------------------------ LEDGER (exactly 9)
    BOUNTY_GAIN(WorkshopTab.LEDGER, "Bounty Gain", "Multiplies ALL Bounty income: kills, wave rewards, bosses", 100, 100.0, 1.16, 50.0, 1.20),
    TRIBUTE(WorkshopTab.LEDGER, "Tribute", "Multiplies ALL cash income: kills and wave rewards", 100, 60.0, 1.15, 30.0, 1.18),
    INTEREST(WorkshopTab.LEDGER, "Interest Per Wave", "Cash interest on held cash after each wave (cap 10%, max 40x wave cash)", 50, 200.0, 1.20, 80.0, 1.22, gateWave = 5),
    FREE_ASSAULT(WorkshopTab.LEDGER, "Free Assault", "Chance an in-run ASSAULT purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    FREE_SHIELD(WorkshopTab.LEDGER, "Free Shield", "Chance an in-run SHIELD purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    FREE_LEDGER(WorkshopTab.LEDGER, "Free Ledger", "Chance an in-run LEDGER purchase is free (cap 25%)", 50, 250.0, 1.21, 100.0, 1.23, gateWave = 8),
    ENEMY_DEGRADE(WorkshopTab.LEDGER, "Enemy Degrade Chance", "Chance a spawning enemy uses the stats of an earlier wave (cap 50%, 1..6 waves lower)", 50, 1000.0, 1.30, 400.0, 1.32, gateWave = 15),
    RECOVERY_AMOUNT(WorkshopTab.LEDGER, "Recovery Amount", "Heals % of max health at the end of each wave (cap 50%)", 50, 80.0, 1.17, 40.0, 1.20, gateWave = 3),
    MAX_RECOVERY(WorkshopTab.LEDGER, "Max Recovery", "Cap on the per-wave heal, % of max health (10% -> 100%)", 90, 80.0, 1.17, 40.0, 1.20, gateWave = 3);

    /** base x growth^L up to softFrom, then x softGrowth^(L - softFrom). Finite at every L <= maxLevel. */
    fun bountyCost(permanentLevel: Int): Double = curve(bountyBase, bountyGrowth, bountySoftFrom, bountySoftGrowth, permanentLevel)
    fun cashCost(runLevel: Int): Double = curve(cashBase, cashGrowth, cashSoftFrom, cashSoftGrowth, runLevel)
    private fun curve(base: Double, g: Double, from: Int, soft: Double, l: Int): Double =
        if (l <= from) floor(base * g.pow(l)) else floor(base * g.pow(from) * soft.pow(l - from))

    /** Primary effect at TOTAL level [l] (units in [format]). */
    fun value(l: Int): Double = when (this) {
        DAMAGE -> 10.0 * (1 + 0.25 * l) * 1.012.pow(l)
        ATTACK_SPEED -> (1.0 + 0.039 * l).coerceAtMost(MAX_WORKSHOP_FIRE_RATE)
        CRIT_CHANCE -> (0.01 * l).coerceAtMost(0.90)
        CRIT_FACTOR -> 1.5 + 0.08 * l
        RANGE -> (1.2 + 1.2 * l / 90.0).coerceAtMost(2.4)
        MULTISHOT -> (0.5 * l / 110.0).coerceAtMost(0.5)
        RAPID_FIRE -> (0.25 * l / 60.0).coerceAtMost(0.25)
        BOUNCE_SHOT -> (0.5 * l / 60.0).coerceAtMost(0.5)
        SUPER_CRIT -> (0.4 * l / 95.0).coerceAtMost(0.4)
        REND_ARMOR -> (0.2 * l / 60.0).coerceAtMost(0.2)
        HULL_INTEGRITY -> hullIntegrity(l)
        CORE_REPAIR -> if (l <= 0) 0.0 else CORE_REPAIR_FRACTION * hullIntegrity(l)   // HP/s before research/perk/enhancement
        BARRIER -> 0.75 * (1 - 0.985.pow(l))
        PLATING -> plating(l)
        COUNTERFIRE -> (0.02 * l).coerceAtMost(1.0)
        DRAIN -> (0.001 * l).coerceAtMost(0.05)
        REPULSE -> (0.01 * l).coerceAtMost(0.5)
        SATELLITES -> orbCount(l).toDouble()
        PULSE -> if (l == 0) 0.0 else (12.0 - 0.1 * l).coerceAtLeast(4.0)     // seconds between pulses
        LAST_STAND -> (0.005 * l).coerceAtMost(0.3)
        FORTIFICATION -> (0.05 * l).coerceAtMost(2.0)                                // x tower max health
        RECONSTRUCTION -> (30.0 - 0.4 * l).coerceAtLeast(8.0)                        // seconds to rebuild
        CHARGES -> if (l == 0) 0.0 else (10.0 - 0.1 * l).coerceAtLeast(2.0)    // seconds between mines
        BOUNTY_GAIN, TRIBUTE -> 1.0 + 0.03 * l
        INTEREST -> (0.002 * l).coerceAtMost(0.10)
        FREE_ASSAULT, FREE_SHIELD, FREE_LEDGER -> (0.005 * l).coerceAtMost(0.25)
        ENEMY_DEGRADE -> (0.01 * l).coerceAtMost(0.5)
        RECOVERY_AMOUNT -> (0.01 * l).coerceAtMost(0.5)
        MAX_RECOVERY -> (0.10 + 0.01 * l).coerceAtMost(1.0)
    }

    /** Secondary effect where an upgrade has two numbers. */
    fun secondary(l: Int): Double = when (this) {
        MULTISHOT -> (1 + 2 * l / 110).coerceAtMost(3).toDouble()          // extra targets
        RAPID_FIRE -> (2.0 + l / 60.0).coerceAtMost(3.0)              // fire-rate multiplier during burst
        BOUNCE_SHOT -> (1 + 2 * l / 60).coerceAtMost(3).toDouble()        // bounces
        SUPER_CRIT -> 1.5 + 0.05 * l                                  // super-crit factor
        REND_ARMOR -> (2.0 * l / 60.0).coerceAtMost(2.0)                    // max total shred
        REPULSE -> (0.02 + 0.0012 * l).coerceAtMost(0.08)           // push distance
        SATELLITES -> 0.5 + 0.05 * l                                        // orb damage x attack
        PULSE -> 1.0 + 0.2 * l                                    // pulse damage x attack
        RECONSTRUCTION -> (0.001 * l).coerceAtMost(0.05)                // wall regen /s
        CHARGES -> 2.0 + 0.3 * l                                   // mine damage x attack
        ENEMY_DEGRADE -> (1 + l / 10).toDouble()                      // max waves degraded
        else -> 0.0
    }

    fun format(l: Int): String {
        fun pct(v: Double) = "%.1f%%".format(v * 100)
        return when (this) {
            DAMAGE, HULL_INTEGRITY -> NumberFormat.compact(value(l))
            PLATING -> "-" + NumberFormat.compact(value(l))
            ATTACK_SPEED -> "%.2f/s".format(value(l))
            CRIT_FACTOR, BOUNTY_GAIN, TRIBUTE -> "x%.2f".format(value(l))
            RANGE -> "%.2f".format(value(l))
            MULTISHOT -> "${pct(value(l))} +${secondary(l).toInt()}"
            RAPID_FIRE -> pct(value(l)) + " x" + "%.2f".format(secondary(l))
            BOUNCE_SHOT -> "${pct(value(l))} x${secondary(l).toInt()}"
            SUPER_CRIT -> pct(value(l)) + " x" + "%.2f".format(secondary(l))
            REND_ARMOR -> "${pct(value(l))}/hit max ${pct(secondary(l))}"
            CORE_REPAIR -> NumberFormat.compact(value(l)) + "/s (max 2.8%/s)"
            REPULSE -> pct(value(l))
            SATELLITES -> "${value(l).toInt()} satellites x%.2f".format(secondary(l))
            PULSE -> if (l == 0) "off" else "every %.1fs x%.1f".format(value(l), secondary(l))
            FORTIFICATION -> if (l == 0) "none" else "${pct(value(l))} HP"
            RECONSTRUCTION -> "%.0fs".format(value(l)) + ", +" + pct(secondary(l)) + "/s"
            CHARGES -> if (l == 0) "off" else "every %.1fs x%.1f".format(value(l), secondary(l))
            ENEMY_DEGRADE -> "${pct(value(l))} (1-${secondary(l).toInt()} waves)"
            else -> pct(value(l))
        }
    }

    companion object {
        /**
         * Hull Integrity (max health) curve, shaped like The Tower's Health: 12 at L0, relative per-level
         * gains shrink through the middle (cubic: ~3/(l+10) per level), then an exponential kicker from
         * L7000 steepens it again. Smooth (C1) and monotonic, finite to L10,000.
         *   12 x (1 + l/10)^3 x exp(HULL_KICKER x max(0, (l - 7000)/1000)^2),  HULL_KICKER = 1.13
         * L1000 ~1.24e7 · L3000 ~3.27e8 · L5000 ~1.51e9 · L7000 ~4.13e9 · L8000 ~1.91e10 · L10000 ~3.1e14
         */
        fun hullIntegrity(l: Int): Double {
            val poly = 12.0 * (1 + l / 10.0).pow(3)
            val over = ((l - HULL_KICK_FROM) / 1000.0).coerceAtLeast(0.0)
            return poly * kotlin.math.exp(HULL_KICKER * over * over)
        }
        const val HULL_KICK_FROM = 7000
        const val HULL_KICKER = 1.13
        /** Core Repair at level L restores this fraction of Hull Integrity(L) per second. */
        const val CORE_REPAIR_FRACTION = 0.01
        /** Effective Core Repair = min(flat HP/s, this x current max health) per second. */
        const val CORE_REPAIR_MAX_FRACTION = 0.028

        /**
         * Plating (flat damage block) curve, shaped like The Tower's Defense Absolute: 0 at L0, relative
         * per-level gains (1/l + 1.5/(l+40)) shrink through the middle, then the same kind of kicker
         * accelerates it past L5000. Smooth and monotonic.
         *   0.6 x l x (1 + l/40)^1.5 x exp(K x max(0, (l - 5000)/1000)^2),  K chosen so L6000 ~ 2.15e7
         * L1000 ~7.9e4 · L3000 ~1.2e6 · L5000 ~4.3e6 · L6000 ~2.15e7 · L8000 ~5.1e11 · L10000 ~1.2e20
         */
        fun plating(l: Int): Double {
            if (l <= 0) return 0.0
            val poly = 0.6 * l * (1 + l / 40.0).pow(1.5)
            val over = ((l - 5000) / 1000.0).coerceAtLeast(0.0)
            return poly * kotlin.math.exp(PLATING_KICKER * over * over)
        }
        val PLATING_KICKER = kotlin.math.ln(2.15e7 / (0.6 * 6000 * (1 + 6000 / 40.0).pow(1.5)))
        /** Workshop Attack Speed reaches this at L1000 (1 + 0.039 x 1000 = 40). */
        const val MAX_WORKSHOP_FIRE_RATE = 40.0
        fun ofTab(t: WorkshopTab) = entries.filter { it.tab == t }
        fun orbCount(l: Int) = ((l + 9) / 10).coerceAtMost(4)
        fun freeUpgradeFor(t: WorkshopTab) = when (t) {
            WorkshopTab.ASSAULT -> FREE_ASSAULT; WorkshopTab.SHIELD -> FREE_SHIELD; WorkshopTab.LEDGER -> FREE_LEDGER
        }
        /** Interest is capped per wave at this many times the wave's base cash value. */
        const val INTEREST_CAP_WAVE_CASH = 40.0
    }
}

/** Permanent workshop purchases (Bounty). */
object Workshop {
    fun gateOpen(u: Upgrade, save: SaveData, bypass: Boolean) = Gates.open(u.gateWave, save, bypass)

    fun canBuy(u: Upgrade, save: SaveData, bypass: Boolean): Boolean {
        val l = save.level(u)
        return gateOpen(u, save, bypass) && l < u.maxLevel && save.bounty >= u.bountyCost(l)
    }

    fun buy(u: Upgrade, save: SaveData, bypass: Boolean): Boolean {
        if (!canBuy(u, save, bypass)) return false
        val l = save.level(u)
        save.bounty -= u.bountyCost(l)
        save.workshop[u.name] = l + 1
        return true
    }
}
