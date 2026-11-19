package com.toasteralley.emberwatch.core

/**
 * Mob visual vocabulary. Every mob is drawn from a cached unit-size Path for its
 * [MobShape], tinted with one of [Palette.MOB_COLORS] and stroked/filled with one
 * of the [OutlineStyle]s. Extras (size tier, rotation, pulse, inner shape) only
 * change the canvas transform or alpha, so the draw cost stays a handful of calls.
 *
 * Deliberately excluded shapes: circle, square, triangle, diamond.
 */
enum class MobShape(val tier: MobTier) {
    // common
    ELLIPSE(MobTier.COMMON),
    ROUNDED_RECT(MobTier.COMMON),
    PARALLELOGRAM(MobTier.COMMON),
    // mid
    PENTAGON(MobTier.MID),
    HEXAGON(MobTier.MID),
    TRAPEZOID(MobTier.MID),
    RING(MobTier.MID),
    // dangerous
    HEPTAGON(MobTier.DANGEROUS),
    OCTAGON(MobTier.DANGEROUS),
    CHEVRON(MobTier.DANGEROUS),
    ARROWHEAD(MobTier.DANGEROUS),
    CROSS(MobTier.DANGEROUS),
    // boss-level (regular mobs that hit like mini-bosses)
    NONAGON(MobTier.BOSS_LEVEL),
    DECAGON(MobTier.BOSS_LEVEL),
    STAR(MobTier.BOSS_LEVEL),
    CLOVER(MobTier.BOSS_LEVEL),
    HEART(MobTier.BOSS_LEVEL),
    // endgame
    YIN_YANG(MobTier.ENDGAME),   // rare endgame elite
    SPIRAL(MobTier.ENDGAME),     // reserved for the Spiral Boss
    CRESCENT(MobTier.MID);       // listed last to keep ordinal ids stable for saves

    companion object {
        /** Names that must never appear in the shape list (they resemble another game). */
        val FORBIDDEN = setOf("CIRCLE", "SQUARE", "TRIANGLE", "DIAMOND")
        /** Shapes regular (non-boss) waves may spawn. */
        val SPAWNABLE: List<MobShape> = entries.filter { it != SPIRAL }
        fun ofTier(t: MobTier) = SPAWNABLE.filter { it.tier == t }
    }
}

enum class MobTier { COMMON, MID, DANGEROUS, BOSS_LEVEL, ENDGAME }

enum class OutlineStyle { SOLID, DASHED, DOTTED, DOUBLE_LINE, GLOW }

enum class SizeTier(val scale: Float) { SMALL(0.8f), MEDIUM(1f), LARGE(1.25f) }

object Palette {
    // Brand: cold black / steel blue / muted coral
    const val BG = 0xFF0B0E13.toInt()
    const val BG_PANEL = 0xFF141A22.toInt()
    const val STEEL = 0xFF4A6F8A.toInt()
    const val STEEL_LIGHT = 0xFF8FB3CC.toInt()
    const val CORAL = 0xFFD9776A.toInt()
    const val CORAL_DIM = 0xFF9E5249.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val TEXT_DIM = 0xFF8792A0.toInt()
    // Ultimate Weapon accents (cheap primitives)
    const val GOLD = 0xFFE3C77A.toInt()
    const val SWAMP = 0xFF7A9E5E.toInt()
    const val VOID = 0xFF05070A.toInt()

    /** 20 mob tints, desaturated to sit on the cold-black field. */
    val MOB_COLORS: IntArray = intArrayOf(
        0xFFD9776A.toInt(), 0xFFE0A26B.toInt(), 0xFFE3C77A.toInt(), 0xFFB9C97A.toInt(),
        0xFF7FBF8E.toInt(), 0xFF6CC2B5.toInt(), 0xFF67AFC9.toInt(), 0xFF8FB3CC.toInt(),
        0xFF6F8FD9.toInt(), 0xFF8C7FD6.toInt(), 0xFFAE7FCC.toInt(), 0xFFCC7FB8.toInt(),
        0xFFD97F95.toInt(), 0xFFB5615A.toInt(), 0xFFC2B8A3.toInt(), 0xFF9AA5B1.toInt(),
        0xFFD4D9DE.toInt(), 0xFF5E8A9E.toInt(), 0xFF7A9E5E.toInt(), 0xFFB08A5E.toInt(),
    )
}

/**
 * Compact visual identity: shapeId + colorIndex + styleFlag, plus optional extras.
 * [innerShape] is -1 for none.
 */
data class MobLook(
    val shape: MobShape,
    val colorIndex: Int,
    val style: OutlineStyle,
    val size: SizeTier = SizeTier.MEDIUM,
    val rotationSpeed: Float = 0f,  // rad/s, 0 = static
    val pulsing: Boolean = false,
    val innerShape: Int = -1,
) {
    companion object {
        /** shape x color x style. */
        fun baseComboCount(): Int =
            MobShape.entries.size * Palette.MOB_COLORS.size * OutlineStyle.entries.size

        /**
         * Including extras: size tier (3) x rotating? (2) x pulsing? (2) x
         * inner shape (none + 20 shapes = 21).
         */
        fun comboCountWithExtras(): Long =
            baseComboCount().toLong() * SizeTier.entries.size * 2 * 2 * (MobShape.entries.size + 1)

        fun random(shape: MobShape, rng: kotlin.random.Random): MobLook = MobLook(
            shape = shape,
            colorIndex = rng.nextInt(Palette.MOB_COLORS.size),
            style = OutlineStyle.entries[rng.nextInt(OutlineStyle.entries.size)],
            size = SizeTier.entries[rng.nextInt(SizeTier.entries.size)],
            rotationSpeed = if (rng.nextFloat() < 0.4f) (rng.nextFloat() - 0.5f) * 3f else 0f,
            pulsing = rng.nextFloat() < 0.25f,
            innerShape = if (rng.nextFloat() < 0.2f) rng.nextInt(MobShape.entries.size) else -1,
        )
    }
}
