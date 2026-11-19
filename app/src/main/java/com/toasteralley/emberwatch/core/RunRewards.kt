package com.toasteralley.emberwatch.core

/** Meta rewards applied when a run ends (coins are banked live by the engine host). */
object RunRewards {
    const val MILESTONE_DIAMONDS = 5L

    /**
     * Updates best wave / tier unlocks and pays first-time milestone Diamonds
     * (every 10 waves of best-wave progress). Returns diamonds granted here.
     */
    fun applyRunEnd(save: SaveData, waveReached: Int, tier: Int): Long {
        save.totalRuns++
        if (waveReached > save.bestWave) save.bestWave = waveReached
        if (waveReached >= Gates.TIER_UNLOCK_WAVE && tier >= save.unlockedTier && tier < Tiers.MAX_TIER) {
            save.unlockedTier = tier + 1
        }
        var granted = 0L
        val milestone = (save.bestWave / 10) * 10
        while (save.milestoneRewardedWave < milestone) {
            save.milestoneRewardedWave += 10
            granted += MILESTONE_DIAMONDS
        }
        save.diamonds += granted
        return granted
    }
}
