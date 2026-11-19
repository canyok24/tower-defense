package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMigrationTest {
    @Test fun v1TiersBecomeStages() {
        val v1 = SaveData().apply { version = 1; bestWave = 61; coins = 1234.0; diamonds = 30 }
        val m = SaveMigrations.migrate(v1, 1, LegacyFields(unlockedTier = 3, selectedTier = 2))
        assertEquals(2, m.version)
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
