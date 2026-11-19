package com.toasteralley.emberwatch.core

/** Meta rewards applied when a run ends (bounty are banked live by the engine host). */
object RunRewards {
    /**
     * Updates the global best wave (used by menu / research gates) and the stage's
     * best cleared wave, paying any newly reached stage milestones.
     * @param waveReached the wave the run was on when it ended (that wave is not cleared)
     */
    fun applyRunEnd(save: SaveData, waveReached: Int, stage: Int): StageReward {
        val oldBest = save.bestWave
        save.totalRuns++
        if (waveReached > save.bestWave) save.bestWave = waveReached
        val claimedBefore = Stages.claimed(save, stage)
        val wasComplete = Stages.isComplete(save, stage)
        val r = Stages.recordWave(save, stage, (waveReached - 1).coerceAtLeast(0))
        // Stone events (GameEvents): best-wave milestones, stage milestones / clear, achievements.
        val events = ArrayList<GameEvent>()
        events += GameEvent.BestWave(oldBest, save.bestWave)
        for (i in claimedBefore until Stages.claimed(save, stage)) events += GameEvent.StageMilestone(stage, i)
        if (!wasComplete && Stages.isComplete(save, stage)) events += GameEvent.StageClear(stage)
        events += GameEvents.checkAchievements(save)
        return r.copy(stones = GameEvents.handleAll(save, events))
    }
}
