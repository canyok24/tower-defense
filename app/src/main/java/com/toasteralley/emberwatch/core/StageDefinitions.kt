package com.toasteralley.emberwatch.core

import kotlin.math.pow
import kotlin.math.roundToInt

/** Reward granted the first time a stage's best wave reaches a milestone. */
data class StageReward(
    val diamonds: Long,
    val cores: Long,
    /** Permanent bounty bonus as a fraction (0.02 = x1.02), stacked multiplicatively. */
    val bountyBonus: Double,
)

data class StageMilestone(val wave: Int, val reward: StageReward)

/**
 * One of the 24 stages (replaces the old 10 difficulty tiers).
 * @param unlockWave wave that must be cleared in the PREVIOUS stage to unlock this one (0 for Stage 1)
 * @param fullRewardWave waves cleared at which every reward of this stage has been earned
 */
data class StageDef(
    val number: Int,
    val unlockWave: Int,
    val fullRewardWave: Int,
    val hpMult: Double,
    val damageMult: Double,
    val speedMult: Double,
    val bountyMult: Double,
    val milestones: List<StageMilestone>,
)

/**
 * THE stage table. All stage tuning lives here.
 *
 *  unlockWave(n)     = 500 x 1.15^(n-2), rounded to 50       (stage 2: 500 ... stage 24: ~10,800)
 *  fullRewardWave(n) = 10,000 + 2,500 x (n-1)                (stage 1: 10,000 ... stage 24: 67,500)
 *  hpMult(n)         = 4^(n-1)       damageMult(n) = 3^(n-1)
 *  speedMult(n)      = 1 + 0.03 x (n-1)                      (stage 24: x1.69)
 *  bountyMult(n)       = 2.2^(n-1)                             (stage 24: ~7.4e7)
 *
 * Milestones: 11 per stage at these fractions of fullRewardWave
 *   (10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 7500, 10000) / 10,000
 * i.e. Stage 1 pays at waves 10, 25, 50, ... 10,000; the last one completes the stage.
 * Rewards scale by (1 + 0.5 x (n-1)) per stage; bounty bonuses are the same each stage
 * (+14.4% per fully completed stage, ~x25 across all 24).
 */
object StageDefinitions {
    const val STAGE_COUNT = 24
    const val STAGE_1_FULL_REWARD_WAVE = 10_000
    const val STAGE_2_UNLOCK_WAVE = 500

    private val MILESTONE_FRACTIONS = intArrayOf(10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 7500, 10000)
    private val BASE_DIAMONDS = longArrayOf(10, 15, 25, 40, 60, 100, 150, 250, 400, 500, 1000)
    private val BASE_CORES = longArrayOf(2, 4, 6, 10, 20, 35, 50, 80, 120, 160, 300)
    private val BOUNTY_BONUS = doubleArrayOf(0.0, 0.0, 0.0, 0.01, 0.01, 0.01, 0.02, 0.02, 0.02, 0.02, 0.03)

    private fun round50(v: Double) = ((v / 50).roundToInt() * 50).coerceAtLeast(50)

    val ALL: List<StageDef> = (1..STAGE_COUNT).map { n ->
        val full = STAGE_1_FULL_REWARD_WAVE + 2_500 * (n - 1)
        val scale = 1 + 0.5 * (n - 1)
        StageDef(
            number = n,
            unlockWave = if (n == 1) 0 else round50(STAGE_2_UNLOCK_WAVE * 1.15.pow(n - 2)),
            fullRewardWave = full,
            hpMult = 4.0.pow(n - 1),
            damageMult = 3.0.pow(n - 1),
            speedMult = 1 + 0.03 * (n - 1),
            bountyMult = 2.2.pow(n - 1),
            milestones = MILESTONE_FRACTIONS.indices.map { i ->
                StageMilestone(
                    wave = (full.toLong() * MILESTONE_FRACTIONS[i] / 10_000).toInt(),
                    reward = StageReward((BASE_DIAMONDS[i] * scale).toLong(), (BASE_CORES[i] * scale).toLong(), BOUNTY_BONUS[i]),
                )
            },
        )
    }

    fun get(n: Int): StageDef = ALL[(n - 1).coerceIn(0, STAGE_COUNT - 1)]
}

/** Stage progress over a [SaveData]. */
object Stages {
    fun best(save: SaveData, n: Int) = save.stageBestWave[n] ?: 0

    fun isUnlocked(save: SaveData, n: Int, bypass: Boolean = false): Boolean = when {
        n < 1 || n > StageDefinitions.STAGE_COUNT -> false
        n == 1 || bypass || n <= save.legacyUnlockedStage -> true
        else -> best(save, n - 1) >= StageDefinitions.get(n).unlockWave
    }

    /** Stages unlock in order; returns the last unlocked one. */
    fun highestUnlocked(save: SaveData, bypass: Boolean = false): Int {
        var h = 1
        while (h < StageDefinitions.STAGE_COUNT && isUnlocked(save, h + 1, bypass)) h++
        return h
    }

    fun claimed(save: SaveData, n: Int) = save.stageMilestonesClaimed[n] ?: 0
    fun isComplete(save: SaveData, n: Int) = claimed(save, n) >= StageDefinitions.get(n).milestones.size
    fun nextMilestone(save: SaveData, n: Int): StageMilestone? = StageDefinitions.get(n).milestones.getOrNull(claimed(save, n))

    /** Product of every claimed milestone bounty bonus across all stages. */
    fun bountyBonus(save: SaveData): Double {
        var m = 1.0
        for (def in StageDefinitions.ALL) for (i in 0 until claimed(save, def.number)) m *= 1 + def.milestones[i].reward.bountyBonus
        return m
    }

    /**
     * Records [wavesCleared] on stage [n] (a wave counts once it is completed) and
     * pays every newly reached milestone. Returns the total reward paid now.
     */
    fun recordWave(save: SaveData, n: Int, wavesCleared: Int): StageReward {
        if (wavesCleared > best(save, n)) save.stageBestWave[n] = wavesCleared
        val def = StageDefinitions.get(n)
        var d = 0L; var c = 0L; var bonus = 1.0
        var idx = claimed(save, n)
        while (idx < def.milestones.size && def.milestones[idx].wave <= best(save, n)) {
            val r = def.milestones[idx].reward
            d += r.diamonds; c += r.cores; bonus *= 1 + r.bountyBonus
            idx++
        }
        save.stageMilestonesClaimed[n] = idx
        save.diamonds += d; save.cores += c
        return StageReward(d, c, bonus - 1)
    }
}
