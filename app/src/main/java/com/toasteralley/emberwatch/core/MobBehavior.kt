package com.toasteralley.emberwatch.core

import kotlin.math.pow

/** How a mob moves while descending. */
enum class Movement { STRAIGHT, ZIGZAG, SINE_WEAVE, DASH_PAUSE }

/** How a mob hurts the tower. PIERCING ignores half of the tower's defense. */
enum class DamageType { CONTACT, RANGED, PIERCING }

/**
 * Behaviour profile per shape. Difficulty lives here, not in the visuals.
 * Multipliers are relative to the wave's baseline HP/damage/speed.
 */
data class MobProfile(
    val hp: Double,
    val damage: Double,
    val speed: Float,            // fraction of playfield height per second
    val movement: Movement,
    val damageType: DamageType = DamageType.CONTACT,
    val shield: Double = 0.0,    // extra shield pool as a fraction of HP (absorbs first)
    val splits: Boolean = false, // spawns 2 smaller children on death
    val ranged: Boolean = false, // stops at a standoff line and shoots
    val regen: Double = 0.0,     // fraction of max HP regenerated per second
    val coin: Double = 1.0,      // coin reward multiplier
    val radius: Float = 0.032f,  // fraction of playfield width
)

object MobProfiles {
    fun of(shape: MobShape): MobProfile = when (shape) {
        MobShape.ELLIPSE -> MobProfile(1.0, 1.0, 0.085f, Movement.STRAIGHT)
        MobShape.ROUNDED_RECT -> MobProfile(1.8, 1.0, 0.065f, Movement.STRAIGHT, coin = 1.3)
        MobShape.PARALLELOGRAM -> MobProfile(0.8, 0.8, 0.11f, Movement.ZIGZAG)
        MobShape.PENTAGON -> MobProfile(2.2, 1.4, 0.085f, Movement.SINE_WEAVE, coin = 1.6)
        MobShape.HEXAGON -> MobProfile(2.5, 1.4, 0.07f, Movement.STRAIGHT, shield = 0.6, coin = 1.8)
        MobShape.TRAPEZOID -> MobProfile(2.0, 0.9, 0.07f, Movement.STRAIGHT, DamageType.RANGED, ranged = true, coin = 1.8)
        MobShape.RING -> MobProfile(2.4, 1.2, 0.08f, Movement.SINE_WEAVE, splits = true, coin = 1.7)
        MobShape.CRESCENT -> MobProfile(1.6, 1.6, 0.12f, Movement.ZIGZAG, DamageType.PIERCING, coin = 1.6)
        MobShape.HEPTAGON -> MobProfile(4.0, 2.2, 0.09f, Movement.DASH_PAUSE, coin = 2.6)
        MobShape.OCTAGON -> MobProfile(6.0, 2.0, 0.06f, Movement.STRAIGHT, shield = 1.0, coin = 3.0, radius = 0.038f)
        MobShape.CHEVRON -> MobProfile(2.6, 2.4, 0.15f, Movement.DASH_PAUSE, DamageType.PIERCING, coin = 2.6)
        MobShape.ARROWHEAD -> MobProfile(2.2, 3.0, 0.17f, Movement.STRAIGHT, DamageType.PIERCING, coin = 2.6)
        MobShape.CROSS -> MobProfile(3.6, 1.6, 0.07f, Movement.SINE_WEAVE, DamageType.RANGED, ranged = true, coin = 2.8)
        MobShape.NONAGON -> MobProfile(12.0, 4.0, 0.055f, Movement.STRAIGHT, shield = 1.0, regen = 0.01, coin = 5.0, radius = 0.045f)
        MobShape.DECAGON -> MobProfile(10.0, 3.5, 0.06f, Movement.SINE_WEAVE, splits = true, coin = 5.0, radius = 0.045f)
        MobShape.STAR -> MobProfile(6.0, 2.5, 0.1f, Movement.ZIGZAG, DamageType.RANGED, ranged = true, coin = 5.0)
        MobShape.CLOVER -> MobProfile(8.0, 3.0, 0.08f, Movement.DASH_PAUSE, splits = true, coin = 5.0, radius = 0.042f)
        MobShape.HEART -> MobProfile(9.0, 3.0, 0.07f, Movement.SINE_WEAVE, regen = 0.04, coin = 5.0, radius = 0.042f)
        MobShape.YIN_YANG -> MobProfile(25.0, 6.0, 0.08f, Movement.SINE_WEAVE, DamageType.PIERCING, shield = 1.5, regen = 0.02, coin = 12.0, radius = 0.05f)
        MobShape.SPIRAL -> MobProfile(60.0, 25.0, 0.025f, Movement.STRAIGHT, shield = 0.5, coin = 0.0, radius = 0.11f)
    }
}

/** Wave composition and scaling. Waves are endless; formulas stay finite in Double. */
object WaveRules {
    const val BOSS_EVERY = 10
    /** Base coin value of a Spiral Boss before the wave scale. */
    const val BOSS_BASE_REWARD = 50.0

    fun isBossWave(wave: Int) = wave > 0 && wave % BOSS_EVERY == 0

    /** Spiral Boss reward = base reward x (wave / 10). */
    fun bossReward(wave: Int): Double = BOSS_BASE_REWARD * (wave / 10.0)

    fun mobCount(wave: Int): Int = (8 + wave * 1.1).toInt().coerceAtMost(70)

    /** Exponential growth runs to this wave; after it growth turns polynomial so 67,500+ waves stay finite. */
    const val EXP_KNEE = 1000

    /**
     * Baseline HP for an "ellipse" at [wave]:
     *   6 x (1 + 0.22(w-1)) x 1.035^min(w-1, 1000) x (max(w,1000)/1000)^4
     * (wave 1000 ~ 1.2e18 base; wave 67,500 ~ 1.5e27 - finite for every stage).
     */
    fun hpScale(wave: Int): Double =
        6.0 * (1 + 0.22 * (wave - 1)) * 1.035.pow(minOf(wave - 1, EXP_KNEE)) * (maxOf(wave, EXP_KNEE) / EXP_KNEE.toDouble()).pow(4)

    fun damageScale(wave: Int): Double =
        4.0 * (1 + 0.12 * (wave - 1)) * 1.025.pow(minOf(wave - 1, EXP_KNEE)) * (maxOf(wave, EXP_KNEE) / EXP_KNEE.toDouble()).pow(3)

    /**
     * Veteran traits: because dangerous / boss-level shapes stay locked until high
     * Enhancement, common and mid-tier mobs pick up behaviours as waves climb so late
     * waves stay hard regardless of the pool:
     *   wave >= 40  : +shield (grows to +100% HP by wave 440)
     *   wave >= 120 : straight movers start weaving (zigzag / sine)
     *   wave >= 200 : regeneration +1%/s (+2%/s from wave 600)
     *   wave >= 300 : contact damage becomes piercing (ignores half of defense)
     *   wave >= 500 : non-ranged mobs split on death
     *   speed       : +0.4%/wave, capped at x1.8 (see speedScale)
     * Profiles that already have a trait keep the stronger value.
     */
    fun veteran(p: MobProfile, wave: Int): MobProfile {
        if (wave < 40) return p
        val shield = (0.25 + (wave - 40) / 400.0 * 0.75).coerceAtMost(1.0)
        return p.copy(
            shield = maxOf(p.shield, shield),
            movement = if (wave >= 120 && p.movement == Movement.STRAIGHT) (if (wave % 2 == 0) Movement.ZIGZAG else Movement.SINE_WEAVE) else p.movement,
            regen = if (wave >= 600) maxOf(p.regen, 0.02) else if (wave >= 200) maxOf(p.regen, 0.01) else p.regen,
            damageType = if (wave >= 300 && p.damageType == DamageType.CONTACT) DamageType.PIERCING else p.damageType,
            splits = p.splits || (wave >= 500 && !p.ranged),
        )
    }

    fun speedScale(wave: Int): Float = (1f + wave * 0.004f).coerceAtMost(1.8f)

    /** Coin value of a regular ellipse kill, before multipliers. */
    fun coinScale(wave: Int): Double = 1.0 + wave * 0.08

    /** In-run cash per ellipse kill. */
    fun cashScale(wave: Int): Double = 2.0 + wave * 0.25

    /** Relative spawn weights by tier; mix shifts toward harder tiers as waves climb. */
    fun tierWeights(wave: Int): Map<MobTier, Double> = mapOf(
        MobTier.COMMON to (100.0 - wave * 1.2).coerceAtLeast(8.0),
        MobTier.MID to if (wave >= 3) (wave * 2.0).coerceAtMost(60.0) else 0.0,
        MobTier.DANGEROUS to if (wave >= 12) ((wave - 11) * 1.5).coerceAtMost(50.0) else 0.0,
        MobTier.BOSS_LEVEL to if (wave >= 30) ((wave - 29) * 0.8).coerceAtMost(35.0) else 0.0,
        MobTier.ENDGAME to if (wave >= 75) ((wave - 74) * 0.1).coerceAtMost(6.0) else 0.0,
    )

    /** Picks a shape: wave sets the tier mix, Enhancement level ([EnemyPool]) sets which tiers may appear. */
    fun pickShape(wave: Int, rng: kotlin.random.Random, enhancementLevel: Int = Int.MAX_VALUE): MobShape {
        val allowed = EnemyPool.allowedTiers(enhancementLevel)
        val w = tierWeights(wave).filter { (t, v) -> v > 0 && t in allowed }
            .ifEmpty { mapOf(MobTier.COMMON to 1.0) }
        var roll = rng.nextDouble() * w.values.sum()
        var tier = MobTier.COMMON
        for ((t, weight) in w) { roll -= weight; if (roll <= 0) { tier = t; break } }
        val pool = MobShape.ofTier(tier)
        return pool[rng.nextInt(pool.size)]
    }
}

/**
 * Which normal-mob tiers can spawn, unlocked by Enhancement level (not by wave).
 * Thresholds are constants so they keep working when ENHANCEMENT_CAP rises past 1000.
 *   level 0..1000    : common + mid
 *   level 1001..5000 : + dangerous
 *   level 5001+      : + boss-level + endgame (yin-yang)
 * The Spiral is never in the pool: it only appears as the boss every 10 waves.
 */
object EnemyPool {
    const val DANGEROUS_UNLOCK_LEVEL = 1001
    const val ALL_UNLOCK_LEVEL = 5001

    fun allowedTiers(enhancementLevel: Int): Set<MobTier> = buildSet {
        add(MobTier.COMMON); add(MobTier.MID)
        if (enhancementLevel >= DANGEROUS_UNLOCK_LEVEL) add(MobTier.DANGEROUS)
        if (enhancementLevel >= ALL_UNLOCK_LEVEL) { add(MobTier.BOSS_LEVEL); add(MobTier.ENDGAME) }
    }

    fun allowedShapes(enhancementLevel: Int): Set<MobShape> =
        allowedTiers(enhancementLevel).flatMap { MobShape.ofTier(it) }.toSet()
}
