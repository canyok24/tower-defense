package com.toasteralley.emberwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopTest {
    private fun names(t: WorkshopTab) = Upgrade.ofTab(t).map { it.label }

    @Test fun tabsHaveExactlyTheListedUpgrades() {
        assertEquals(listOf("Damage", "Attack Speed", "Crit Chance", "Crit Factor", "Range", "Multishot", "Rapid Fire",
            "Bounce Shot", "Super Crit", "Rend Armor"), names(WorkshopTab.ASSAULT))
        assertEquals(listOf("Health", "Health Regen", "Defense %", "Defense Absolute", "Thorns", "Lifesteal", "Knockback",
            "Orbs", "Shockwave", "Death Defy", "Wall Health", "Wall Rebuild", "Land Mines"), names(WorkshopTab.SHIELD))
        assertEquals(9, Upgrade.ofTab(WorkshopTab.LEDGER).size)
        assertEquals(listOf("Bounty", "Tribute", "Interest Per Wave", "Free Assault", "Free Shield", "Free Ledger",
            "Enemy Degrade Chance", "Recovery Amount", "Max Recovery"), names(WorkshopTab.LEDGER))
        assertEquals(listOf("ASSAULT", "SHIELD", "LEDGER"), WorkshopTab.entries.map { it.label })
        val banned = listOf("Utility", "Defense tab", "Coins Per Kill", "Cash Bonus", "Enemy Level Skip")
        for (u in Upgrade.entries) for (b in banned) {
            assertFalse(u.label.contains(b, ignoreCase = true)); assertFalse(u.description.contains(b, ignoreCase = true))
        }
    }

    @Test fun percentagesAreCapped() {
        for (u in Upgrade.entries) {
            val v = u.value(u.maxLevel); assertTrue(u.name, v.isFinite())
            assertTrue(u.name, u.value(u.maxLevel + 500).isFinite())
        }
        assertEquals(0.79, Upgrade.CRIT_CHANCE.value(1000), 1e-9)
        assertEquals(0.25, Upgrade.FREE_ASSAULT.value(1000), 1e-9)
        assertEquals(0.5, Upgrade.ENEMY_DEGRADE.value(1000), 1e-9)
        assertEquals(0.3, Upgrade.DEATH_DEFY.value(1000), 1e-9)
        assertEquals(0.05, Upgrade.LIFESTEAL.value(1000), 1e-9)
        assertEquals(1.0, Upgrade.MAX_RECOVERY.value(1000), 1e-9)
        assertEquals(4, Upgrade.orbCount(1000))
        assertTrue(Upgrade.DEFENSE_PCT.value(10_000) <= 0.75)
    }

    @Test fun permanentAndRunLevelsStack() {
        val s = SaveData().apply { coins = 1e6; workshop[Upgrade.DAMAGE.name] = 10 }
        val e = GameEngine(s, emptyMap(), 1.0, seed = 1)
        val before = e.stats.damage
        assertEquals(TowerStats.resolve(s).damage, before, 1e-9)
        e.debugAddCash(1e6)
        assertEquals(BuyResult.BOUGHT, e.buyRunUpgrade(Upgrade.DAMAGE))
        assertEquals(11, e.totalLevel(Upgrade.DAMAGE))
        assertEquals(Upgrade.DAMAGE.value(11) * e.stats.enhancement, e.stats.damage, 1e-9)
        assertEquals(10, s.level(Upgrade.DAMAGE)) // permanent untouched; run levels reset with the next engine
        assertEquals(0, GameEngine(s, emptyMap(), 1.0).runLevel(Upgrade.DAMAGE))
        // gate + max respected in-run
        assertEquals(BuyResult.LOCKED, e.buyRunUpgrade(Upgrade.SUPER_CRIT))
        s.workshop[Upgrade.RANGE.name] = Upgrade.RANGE.maxLevel
        assertEquals(BuyResult.MAXED, GameEngine(s, emptyMap(), 1.0).buyRunUpgrade(Upgrade.RANGE))
        // permanent purchase with coins
        assertTrue(Workshop.buy(Upgrade.HEALTH, s, false))
        assertEquals(1, s.level(Upgrade.HEALTH))
        assertTrue(s.coins < 1e6)
    }

    @Test fun freeUpgradeChanceApplied() {
        val s = SaveData().apply { bestWave = 100; workshop[Upgrade.FREE_ASSAULT.name] = 50 }
        val e = GameEngine(s, emptyMap(), 1.0, seed = 42)
        e.debugAddCash(1e300)
        var spent = 0.0; var free = 0
        repeat(400) {
            val cost = e.runUpgradeCost(Upgrade.DAMAGE); val cash = e.cash
            if (e.buyRunUpgrade(Upgrade.DAMAGE) == BuyResult.FREE) { free++; assertEquals(cash, e.cash, 0.0) } else spent += cost
        }
        assertTrue("free=$free", free in 60..140)       // ~25% of 400
        assertEquals(free, e.freePurchases)
        // other tabs don't get Free Assault
        repeat(100) { assertTrue(e.buyRunUpgrade(Upgrade.HEALTH) != BuyResult.FREE) }
    }

    @Test fun enemyDegradeLowersStatWave() {
        val s = SaveData().apply { workshop[Upgrade.ENEMY_DEGRADE.name] = 50 }
        val e = GameEngine(s, emptyMap(), 1.0, startWave = 100, seed = 3)
        val mobs = (1..400).map { e.spawnMob(MobShape.ELLIPSE) }
        val degraded = mobs.filter { it.statWave < 100 }
        assertTrue(degraded.size in 140..260)                      // ~50%
        assertTrue(degraded.all { it.statWave >= 100 - 6 })        // 1..6 waves lower at L50
        val full = mobs.first { it.statWave == 100 }
        val low = degraded.first()
        assertTrue(low.maxHp < full.maxHp)
        assertEquals(WaveRules.hpScale(low.statWave) * WaveRules.veteran(MobProfiles.of(MobShape.ELLIPSE), low.statWave).hp, low.maxHp, low.maxHp * 1e-9)
        assertEquals(full.coins, low.coins, 1e-9)                  // rewards stay at the current wave
        val none = GameEngine(SaveData(), emptyMap(), 1.0, startWave = 100, seed = 3)
        assertTrue((1..200).all { none.spawnMob(MobShape.ELLIPSE).statWave == 100 })
    }

    @Test fun bountyAndTributeInPipelines() {
        val s = SaveData().apply { workshop[Upgrade.BOUNTY.name] = 10; workshop[Upgrade.TRIBUTE.name] = 20 }
        val e = GameEngine(s, emptyMap(), coinMultiplier = 3.0, seed = 1)
        val m = e.spawnMob(MobShape.ELLIPSE, 0.5f, 0.5f)
        val cash0 = e.cash
        e.hit(m, 1e12)
        assertEquals(m.coins * 3.0 * 1.3, e.runCoins, 1e-9)
        assertEquals(m.cash * 1.6, e.cash - cash0, 1e-9)
    }

    @Test fun deathDefy() {
        fun survivals(level: Int): Int {
            var n = 0
            repeat(1000) { seed ->
                val s = SaveData().apply { workshop[Upgrade.DEATH_DEFY.name] = level }
                val e = GameEngine(s, emptyMap(), 1.0, seed = seed.toLong())
                e.hurtTower(e.maxHealth * 10, piercing = true)
                if (e.health > 0) { n++; assertEquals(maxOf(1.0, e.maxHealth * 0.01), e.health, 1e-9) }
            }
            return n
        }
        assertEquals(0, survivals(0))
        assertTrue(survivals(60) in 240..360)                      // cap 30%
    }

    @Test fun wallAbsorbsBeforeTower() {
        val s = SaveData().apply { workshop[Upgrade.WALL_HEALTH.name] = 10 }   // wall = 50% of max HP
        val e = GameEngine(s, emptyMap(), 1.0, seed = 1)
        assertEquals(e.maxHealth * 0.5, e.wallHp, 1e-9)
        val hp0 = e.health
        e.hurtTower(e.maxHealth * 0.3, piercing = false)
        assertEquals(hp0, e.health, 1e-9)
        assertEquals(e.maxHealth * 0.2, e.wallHp, 1e-9)
        e.hurtTower(e.maxHealth * 0.3, piercing = false)           // 0.2 absorbed, 0.1 through
        assertFalse(e.wallUp)
        assertEquals(hp0 - e.maxHealth * 0.1, e.health, 1e-9)
        assertTrue(e.wallLineY < e.towerLineY)
        // contact mobs stop at the wall while it stands
        val e2 = GameEngine(s, emptyMap(), 1.0, seed = 2)
        val m = e2.spawnMob(MobShape.ARROWHEAD, 0.5f, e2.worldH * 0.5f)
        repeat(60 * 20) { e2.step() }
        if (m.alive && m.engaged && e2.wallUp) assertTrue(m.y <= e2.wallLineY)
    }

    @Test fun defenseAbsoluteAndThorns() {
        val s = SaveData().apply { workshop[Upgrade.DEFENSE_ABS.name] = 10; workshop[Upgrade.THORNS.name] = 50 }
        val e = GameEngine(s, emptyMap(), 1.0, seed = 1)
        val abs = Upgrade.DEFENSE_ABS.value(10)
        val m = e.spawnMob(MobShape.ROUNDED_RECT, 0.5f, 1.0f)
        val hp0 = e.health; val mobHp = m.hp + m.shield
        e.hurtTower(abs + 5, piercing = false, attacker = m)
        assertEquals(hp0 - 5, e.health, 1e-9)
        assertTrue(m.hp + m.shield < mobHp || !m.alive)            // 100% thorns reflected
        e.hurtTower(abs / 2, piercing = false)
        assertEquals(hp0 - 5 - abs / 2 * 0.1, e.health, 1e-9)      // min 10% gets through
    }

    @Test fun recoveryCappedByMaxRecovery() {
        val s = SaveData().apply { workshop[Upgrade.RECOVERY_AMOUNT.name] = 50 }  // 50%, max recovery 10%
        val e = GameEngine(s, emptyMap(), 1.0, seed = 1, startWave = 1)
        e.hurtTower(e.maxHealth * 0.6, piercing = true)
        val hp = e.health
        repeat(60 * 40) { if (e.wave == 1) e.step() }
        assertTrue(e.health - hp <= e.maxHealth * 0.10 + 1e-6 + e.maxHealth * 0.0)
    }
}
