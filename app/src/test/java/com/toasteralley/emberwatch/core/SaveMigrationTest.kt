package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMigrationTest {
    @Test fun v1TiersBecomeStages() {
        val v1 = SaveData().apply { version = 1; bestWave = 61; bounty = 1234.0; diamonds = 30 }
        val m = SaveMigrations.migrate(v1, 1, LegacyFields(unlockedTier = 3, selectedTier = 2))
        assertEquals(SaveData.CURRENT_VERSION, m.version)
        assertEquals(3, m.legacyUnlockedStage)
        assertEquals(2, m.selectedStage)
        assertTrue(Stages.isUnlocked(m, 3)); assertFalse(Stages.isUnlocked(m, 4))
        assertEquals(60, Stages.best(m, 1))                  // reached 61 -> cleared 60
        assertEquals(3, Stages.claimed(m, 1))                // milestones 10, 25, 50
        assertEquals(30L + 10 + 15 + 25, m.diamonds)
        assertEquals(1234.0, m.bounty, 0.0)
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
        assertEquals(SaveData.CURRENT_VERSION, s.version)
        assertEquals(30, s.level(Upgrade.DAMAGE))
        assertEquals(290, s.level(Upgrade.ATTACK_SPEED))
        assertEquals(9, s.level(Upgrade.CRIT_CHANCE))
        assertEquals(4, s.level(Upgrade.CRIT_FACTOR))
        assertEquals(12, s.level(Upgrade.HULL_INTEGRITY))
        assertEquals(7, s.level(Upgrade.BARRIER))
        assertEquals(WorkshopTab.ASSAULT, Upgrade.DAMAGE.tab); assertEquals(WorkshopTab.SHIELD, Upgrade.BARRIER.tab)
        assertEquals(setOf("DAMAGE", "ATTACK_SPEED", "CRIT_CHANCE", "CRIT_FACTOR", "HULL_INTEGRITY", "BARRIER"), s.workshop.keys)
    }

    @Test fun v1SaveRunsBothMigrations() {
        val s = SaveData().apply { version = 1; bestWave = 11; workshop["ATTACK"] = 5 }
        SaveMigrations.migrate(s, 1, LegacyFields(unlockedTier = 2, selectedTier = 2))
        assertEquals(SaveData.CURRENT_VERSION, s.version)
        assertEquals(5, s.level(Upgrade.DAMAGE)); assertEquals(2, s.selectedStage)
    }

    @Test fun v3ShieldKeysRenamedAndClamped() {
        val old = mapOf("HEALTH" to 40, "HEALTH_REGEN" to 3, "DEFENSE_PCT" to 9, "DEFENSE_ABS" to 11, "THORNS" to 2,
            "LIFESTEAL" to 4, "KNOCKBACK" to 5, "ORBS" to 6, "SHOCKWAVE" to 7, "DEATH_DEFY" to 8, "WALL_HEALTH" to 10,
            "WALL_REBUILD" to 12, "LAND_MINES" to 13)
        val s = SaveData().apply { version = 3; workshop.putAll(old); workshop["CRIT_FACTOR"] = 500; workshop["DAMAGE"] = 20 }
        SaveMigrations.migrate(s, 3, LegacyFields())
        assertEquals(SaveData.CURRENT_VERSION, s.version)
        for ((k, v) in old) assertEquals(k, v, s.level(SaveMigrations.SHIELD_V3_TO_V4.getValue(k)))
        assertEquals(listOf(Upgrade.HULL_INTEGRITY, Upgrade.CORE_REPAIR, Upgrade.BARRIER, Upgrade.PLATING, Upgrade.COUNTERFIRE,
            Upgrade.DRAIN, Upgrade.REPULSE, Upgrade.SATELLITES, Upgrade.PULSE, Upgrade.LAST_STAND, Upgrade.FORTIFICATION,
            Upgrade.RECONSTRUCTION, Upgrade.CHARGES), old.keys.map { SaveMigrations.SHIELD_V3_TO_V4.getValue(it) })
        assertTrue(old.keys.none { it in s.workshop })
        assertEquals(175, s.level(Upgrade.CRIT_FACTOR))             // Crit Factor max 500 -> 175
        assertEquals(20, s.level(Upgrade.DAMAGE))
        // old Health Regen levels (max 50) carry over 1:1 into Core Repair
        val r = SaveData().apply { version = 3; workshop["HEALTH_REGEN"] = 50 }
        SaveMigrations.migrate(r, 3, LegacyFields())
        assertEquals(50, r.level(Upgrade.CORE_REPAIR))
    }

    @Test fun currentVersionSavesAreClampedOnLoad() {
        val s = SaveData().apply { workshop["CRIT_FACTOR"] = 400; workshop["CRIT_CHANCE"] = 95; workshop["GONE"] = 1 }
        SaveMigrations.clampWorkshop(s)
        assertEquals(175, s.level(Upgrade.CRIT_FACTOR)); assertEquals(90, s.level(Upgrade.CRIT_CHANCE)); assertFalse("GONE" in s.workshop)
    }

    @Test fun v4CoinsBecomeBounty() {
        val s = SaveData().apply { version = 4; workshop["BOUNTY"] = 17; research["COIN_BONUS"] = 6 }
        SaveMigrations.migrate(s, 4, LegacyFields(coins = 98_765.0))
        assertEquals(SaveData.CURRENT_VERSION, s.version)
        assertEquals(98_765.0, s.bounty, 0.0)                         // same single currency, no conversion
        assertEquals(17, s.level(Upgrade.BOUNTY_GAIN)); assertFalse("BOUNTY" in s.workshop)
        assertEquals(6, s.level(ResearchProject.BOUNTY_BONUS)); assertFalse("COIN_BONUS" in s.research)
        assertEquals("Bounty Gain", Upgrade.BOUNTY_GAIN.label)
    }
}
