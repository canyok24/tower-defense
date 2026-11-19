package com.toasteralley.emberwatch.core

/** Fields that existed in older save versions and are only read for migration. */
data class LegacyFields(
    val unlockedTier: Int = 1,
    val selectedTier: Int = 1,
)

/** Pure save migrations (unit-tested; SaveStore only extracts the raw fields). */
object SaveMigrations {
    /** Old v2 WorkshopStat names -> new upgrades. */
    val WORKSHOP_V2_TO_V3: Map<String, Upgrade> = mapOf(
        "ATTACK" to Upgrade.DAMAGE,          // Assault
        "ATTACK_SPEED" to Upgrade.ATTACK_SPEED,
        "CRIT_CHANCE" to Upgrade.CRIT_CHANCE,
        "CRIT_FACTOR" to Upgrade.CRIT_FACTOR,
        "HEALTH" to Upgrade.HEALTH,          // Shield
        "DEFENSE" to Upgrade.DEFENSE_PCT,
    )

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
        if (v < 3) {
            // v2 -> v3: six workshop stats -> three tabs. Keys that kept their name stay.
            for ((old, new) in WORKSHOP_V2_TO_V3) {
                val lvl = save.workshop.remove(old) ?: continue
                save.workshop[new.name] = maxOf(save.workshop[new.name] ?: 0, lvl)
            }
            // Clamp to the new max levels; drop unknown keys.
            for (key in save.workshop.keys.toList()) {
                val u = Upgrade.entries.firstOrNull { it.name == key }
                if (u == null) save.workshop.remove(key) else save.workshop[key] = save.workshop[key]!!.coerceIn(0, u.maxLevel)
            }
            v = 3
        }
        save.version = SaveData.CURRENT_VERSION
        return save
    }
}
