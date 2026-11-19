package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMigrationTest {
    @Test fun v1TiersBecomeStages() {
        val v1 = SaveData().apply { version = 1; bestWave = 61; coins = 1234.0; diamonds = 30 }
        val m = SaveMigrations.migrate(v1, 1, LegacyFields(unlockedTier = 3, selectedTier = 2))
        assertEquals(SaveData.CURRENT_VERSION, m.version)
        assertEquals(3, m.legacyUnlockedStage)
        assertEquals(2, m.selectedStage)
        assertTrue(Stages.isUnlocked(m, 3)); assertFalse(Stages.isUnlocked(m, 4))
        assertEquals(60, Stages.best(m, 1))                  // reached 61 -> cleared 60
        assertEquals(3, Stages.claimed(m, 1))                // milestones 10, 25, 50
        assertEquals(30L + 10 + 15 + 25, m.diamonds)
        assertEquals(1234.0, m.coins, 0.0)
    }

    @Test fun selectedTierClampedToUnlocked() {
        val m = SaveMigrations.migrate(SaveData().apply { version = 1 }, 1, LegacyFields(unlockedTier = 1, selectedTier = 9))
        assertEquals(1, m.selectedStage)
        assertEquals(0, Stages.best(m, 1))
    }

    @Test fun currentVersionUntouched() {
        val s = SaveData().apply { selectedStage = 4; legacyUnlockedStage = 1 }
        SaveMigrations.migrate(s, 2, LegacyFields(unlockedTier = 10, selectedTier = 10))
        assertEquals(4, s.selectedStage); assertEquals(1, s.legacyUnlockedStage)
    }
}

class WorkshopMigrationTest {
    @Test fun v2SixStatsMoveIntoTabs() {
        val s = SaveData().apply {
            version = 2
            workshop["HEALTH"] = 12; workshop["ATTACK"] = 30; workshop["DEFENSE"] = 7
            workshop["ATTACK_SPEED"] = 290; workshop["CRIT_CHANCE"] = 9; workshop["CRIT_FACTOR"] = 4
            workshop["SOMETHING_OLD"] = 3
        }
        SaveMigrations.migrate(s, 2, LegacyFields())
        assertEquals(3, s.version)
        assertEquals(30, s.level(Upgrade.DAMAGE))
        assertEquals(275, s.level(Upgrade.ATTACK_SPEED))           // clamped to new max
        assertEquals(9, s.level(Upgrade.CRIT_CHANCE))
        assertEquals(4, s.level(Upgrade.CRIT_FACTOR))
        assertEquals(12, s.level(Upgrade.HEALTH))
        assertEquals(7, s.level(Upgrade.DEFENSE_PCT))
        assertEquals(WorkshopTab.ASSAULT, Upgrade.DAMAGE.tab); assertEquals(WorkshopTab.SHIELD, Upgrade.DEFENSE_PCT.tab)
        assertEquals(setOf("DAMAGE", "ATTACK_SPEED", "CRIT_CHANCE", "CRIT_FACTOR", "HEALTH", "DEFENSE_PCT"), s.workshop.keys)
    }

    @Test fun v1SaveRunsBothMigrations() {
        val s = SaveData().apply { version = 1; bestWave = 11; workshop["ATTACK"] = 5 }
        SaveMigrations.migrate(s, 1, LegacyFields(unlockedTier = 2, selectedTier = 2))
        assertEquals(SaveData.CURRENT_VERSION, s.version)
        assertEquals(5, s.level(Upgrade.DAMAGE)); assertEquals(2, s.selectedStage)
    }
}
