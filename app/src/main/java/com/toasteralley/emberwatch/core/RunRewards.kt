package com.toasteralley.emberwatch.core

/** Meta rewards applied when a run ends (bounty are banked live by the engine host). */
object RunRewards {
    /**
     * Updates the global best wave (used by menu / research gates) and the stage's
     * best cleared wave, paying any newly reached stage milestones.
     * @param waveReached the wave the run was on when it ended (that wave is not cleared)
     */
    fun applyRunEnd(save: SaveData, waveReached: Int, stage: Int): StageReward {
        save.totalRuns++
        if (waveReached > save.bestWave) save.bestWave = waveReached
        return Stages.recordWave(save, stage, (waveReached - 1).coerceAtLeast(0))
    }
}
