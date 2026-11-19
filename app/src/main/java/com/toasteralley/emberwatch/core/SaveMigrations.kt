package com.toasteralley.emberwatch.core

/** Fields that existed in older save versions and are only read for migration. */
data class LegacyFields(
    val unlockedTier: Int = 1,
    val selectedTier: Int = 1,
    /** Pre-v5 saves stored the currency under "coins" (now "bounty"). */
    val coins: Double? = null,
    /** Pre-v6 Diamond Ultimate Weapon levels (Ember Nova, Frost Lattice, Chain Arc, Gravity Well). */
    val oldUltimates: Map<String, Int> = emptyMap(),
)

/** Pure save migrations (unit-tested; SaveStore only extracts the raw fields). */
object SaveMigrations {
    /** Old v2 WorkshopStat names -> new upgrades. */
    /** v3 -> v4: Shield upgrades renamed (labels and enum names); formulas, caps and costs identical. */
    val SHIELD_V3_TO_V4: Map<String, Upgrade> = mapOf(
        "HEALTH" to Upgrade.HULL_INTEGRITY, "HEALTH_REGEN" to Upgrade.CORE_REPAIR,
        "DEFENSE_PCT" to Upgrade.BARRIER, "DEFENSE_ABS" to Upgrade.PLATING,
        "THORNS" to Upgrade.COUNTERFIRE, "LIFESTEAL" to Upgrade.DRAIN, "KNOCKBACK" to Upgrade.REPULSE,
        "ORBS" to Upgrade.SATELLITES, "SHOCKWAVE" to Upgrade.PULSE, "DEATH_DEFY" to Upgrade.LAST_STAND,
        "WALL_HEALTH" to Upgrade.FORTIFICATION, "WALL_REBUILD" to Upgrade.RECONSTRUCTION, "LAND_MINES" to Upgrade.CHARGES,
    )

    val WORKSHOP_V2_TO_V3: Map<String, Upgrade> = mapOf(
        "ATTACK" to Upgrade.DAMAGE,          // Assault
        "ATTACK_SPEED" to Upgrade.ATTACK_SPEED,
        "CRIT_CHANCE" to Upgrade.CRIT_CHANCE,
        "CRIT_FACTOR" to Upgrade.CRIT_FACTOR,
        "HEALTH" to Upgrade.HULL_INTEGRITY,          // Shield
        "DEFENSE" to Upgrade.BARRIER,
    )

    /**
     * Brings [save] (already filled with all fields that still exist) from
     * [fromVersion] up to [SaveData.CURRENT_VERSION].
     */
    /**
     * Clamp every workshop level to the current max level and drop unknown keys. Runs on EVERY load
     * (not only on a version bump) so a rebalance that lowers a max level (e.g. Crit Factor 500 -> 175)
     * clamps saves written by earlier builds of the same save version.
     */
    /** Clamp stage selection / legacy unlocks to the stages in this build (5). Runs on every load. */
    fun clampStages(save: SaveData): SaveData {
        save.selectedStage = save.selectedStage.coerceIn(1, StageDefinitions.STAGE_COUNT)
        save.legacyUnlockedStage = save.legacyUnlockedStage.coerceIn(1, StageDefinitions.STAGE_COUNT)
        return save
    }

    /** Everything that must hold for any loaded save, whatever its version. */
    fun normalize(save: SaveData): SaveData = clampStages(clampWorkshop(save))

    val OLD_DIAMOND_UWS = setOf("EMBER_NOVA", "FROST_LATTICE", "CHAIN_ARC", "GRAVITY_WELL")

    /** Diamonds spent to reach [level] on an old UW: unlock 20, then level k -> k+1 cost 8 + 6k. */
    fun oldUwDiamondsSpent(level: Int): Long {
        if (level <= 0) return 0
        var total = 20L
        for (k in 1 until level) total += 8 + 6L * k
        return total
    }

    fun clampWorkshop(save: SaveData): SaveData {
        for (key in save.workshop.keys.toList()) {
            val u = Upgrade.entries.firstOrNull { it.name == key }
            if (u == null) save.workshop.remove(key) else save.workshop[key] = save.workshop[key]!!.coerceIn(0, u.maxLevel)
        }
        return save
    }

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
            v = 3
        }
        if (v < 4) {
            for ((old, new) in SHIELD_V3_TO_V4) {
                val lvl = save.workshop.remove(old) ?: continue
                save.workshop[new.name] = maxOf(save.workshop[new.name] ?: 0, lvl)
            }
            v = 4
        }
        if (v < 5) {
            // v4 -> v5: currency "coins" renamed to Bounty (same single pool, no conversion);
            // Ledger "Bounty" multiplier renamed to Bounty Gain; Research Bay COIN_BONUS -> BOUNTY_BONUS.
            legacy.coins?.let { if (save.bounty == 0.0) save.bounty = it }
            save.workshop.remove("BOUNTY")?.let { save.workshop[Upgrade.BOUNTY_GAIN.name] = maxOf(save.workshop[Upgrade.BOUNTY_GAIN.name] ?: 0, it) }
            save.research.remove("COIN_BONUS")?.let { save.research[ResearchProject.BOUNTY_BONUS.name] = maxOf(save.research[ResearchProject.BOUNTY_BONUS.name] ?: 0, it) }
            v = 5
        }
        if (v < 6) {
            // v5 -> v6: the four Diamond UWs are removed (replaced by the nine Stone Ultimates).
            // Refund every Diamond spent on them.
            save.diamonds += legacy.oldUltimates.filterKeys { it in OLD_DIAMOND_UWS }.values.sumOf { oldUwDiamondsSpent(it) }
            v = 6
        }
        normalize(save)
        save.version = SaveData.CURRENT_VERSION
        return save
    }
}
