package com.toasteralley.emberwatch.core

/**
 * Simple data-driven event system that awards Stones.
 * Engine / run-end code emits [GameEvent]s; [GameEvents.handle] maps each to a [StoneAward] using the
 * tables below. Awards with a non-null [StoneAward.onceKey] are paid at most once per save
 * (persisted in SaveData.awardedEvents); repeatable awards (boss kills) have no key.
 */
sealed interface GameEvent {
    /** Best wave (any stage) went from [oldBest] to [newBest]. */
    data class BestWave(val oldBest: Int, val newBest: Int) : GameEvent
    /** A Spiral Boss died on [stage]. */
    data class BossKill(val stage: Int) : GameEvent
    /** Milestone #[index] (0-based) of [stage] was paid. */
    data class StageMilestone(val stage: Int, val index: Int) : GameEvent
    /** All milestones of [stage] are complete. */
    data class StageClear(val stage: Int) : GameEvent
    data class Achievement(val achievement: GameAchievement) : GameEvent
}

enum class GameAchievement(val label: String, val stones: Long) {
    FIRST_RUN("First run", 5),
    FIRST_BOSS("First Spiral Boss", 10),
    WAVE_50("Reach wave 50", 15),
    STAGE_2("Unlock Stage 2", 25),
    FIRST_ULTIMATE("Unlock an Ultimate Weapon", 10),
    WORKSHOP_MAXED("Max every Workshop upgrade", 250),
}

data class StoneAward(val stones: Long, val reason: String, val onceKey: String?)

object GameEvents {
    /** Every [WAVE_MILESTONE_STEP] waves of best wave pays [WAVE_MILESTONE_STONES], once each. */
    const val WAVE_MILESTONE_STEP = 100
    const val WAVE_MILESTONE_STONES = 10L
    /** Spiral Boss: [BOSS_STONES_PER_STAGE] x stage per kill (repeatable). */
    const val BOSS_STONES_PER_STAGE = 1L
    /** Stage milestone: 5 x stage, once per milestone. */
    const val STAGE_MILESTONE_STONES_PER_STAGE = 5L
    /** Stage clear (all milestones): 100 x stage, once per stage. */
    const val STAGE_CLEAR_STONES_PER_STAGE = 100L

    fun awardsFor(e: GameEvent): List<StoneAward> = when (e) {
        is GameEvent.BestWave -> ((e.oldBest / WAVE_MILESTONE_STEP + 1)..(e.newBest / WAVE_MILESTONE_STEP)).map { k ->
            StoneAward(WAVE_MILESTONE_STONES, "Best wave ${k * WAVE_MILESTONE_STEP}", "wave:${k * WAVE_MILESTONE_STEP}")
        }
        is GameEvent.BossKill -> listOf(StoneAward(BOSS_STONES_PER_STAGE * e.stage.coerceAtLeast(1), "Spiral Boss", null))
        is GameEvent.StageMilestone -> listOf(StoneAward(STAGE_MILESTONE_STONES_PER_STAGE * e.stage,
            "Stage ${e.stage} milestone ${e.index + 1}", "stage:${e.stage}:m${e.index}"))
        is GameEvent.StageClear -> listOf(StoneAward(STAGE_CLEAR_STONES_PER_STAGE * e.stage, "Stage ${e.stage} cleared", "stageclear:${e.stage}"))
        is GameEvent.Achievement -> listOf(StoneAward(e.achievement.stones, e.achievement.label, "ach:${e.achievement.name}"))
    }

    /** Applies the awards for [e] to [save]; returns the Stones actually granted (0 if already awarded). */
    fun handle(save: SaveData, e: GameEvent): Long {
        var total = 0L
        for (a in awardsFor(e)) {
            if (a.onceKey != null && a.onceKey in save.awardedEvents) continue
            a.onceKey?.let { save.awardedEvents += it }
            save.stones += a.stones
            total += a.stones
        }
        return total
    }

    /** Achievement checks that only need the save. */
    fun checkAchievements(save: SaveData): List<GameEvent> = buildList {
        if (save.totalRuns >= 1) add(GameEvent.Achievement(GameAchievement.FIRST_RUN))
        if (save.bestWave >= 50) add(GameEvent.Achievement(GameAchievement.WAVE_50))
        if (Stages.isUnlocked(save, 2)) add(GameEvent.Achievement(GameAchievement.STAGE_2))
        if (save.uwUnlockOrder.isNotEmpty()) add(GameEvent.Achievement(GameAchievement.FIRST_ULTIMATE))
        if (MasteryLab.maxedWorkshopCount(save) == MasteryLab.TOTAL_WORKSHOP) add(GameEvent.Achievement(GameAchievement.WORKSHOP_MAXED))
    }

    fun handleAll(save: SaveData, events: List<GameEvent>): Long = events.sumOf { handle(save, it) }
}
