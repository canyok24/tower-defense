package com.toasteralley.emberwatch.core

/** Fields that existed in older save versions and are only read for migration. */
data class LegacyFields(
    val unlockedTier: Int = 1,
    val selectedTier: Int = 1,
)

/** Pure save migrations (unit-tested; SaveStore only extracts the raw fields). */
object SaveMigrations {
    /**
     * Brings [save] (already filled with all fields that still exist) from
     * [fromVersion] up to [SaveData.CURRENT_VERSION].
     */
    fun migrate(save: SaveData, fromVersion: Int, legacy: LegacyFields): SaveData {
        var v = fromVersion
        if (v < 2) {
            // v1 -> v2: "Difficulty Tiers 1-10" became "Stages 1-24".
            // Tier N maps to Stage N. Old tier unlocks were cheaper (wave 50), so we keep
            // every previously unlocked tier unlocked as a stage rather than re-locking it.
            val unlocked = legacy.unlockedTier.coerceIn(1, StageDefinitions.STAGE_COUNT)
            save.legacyUnlockedStage = unlocked
            save.selectedStage = legacy.selectedTier.coerceIn(1, unlocked)
            // The old global best wave becomes Stage 1 progress (waves cleared = reached - 1),
            // paying any Stage 1 milestones already passed.
            if (save.bestWave > 1) Stages.recordWave(save, 1, save.bestWave - 1)
            v = 2
        }
        save.version = SaveData.CURRENT_VERSION
        return save
    }
}
