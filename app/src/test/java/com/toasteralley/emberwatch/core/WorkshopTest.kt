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
        assertEquals(listOf("Hull Integrity", "Core Repair", "Barrier", "Plating", "Counterfire", "Drain", "Repulse",
            "Satellites", "Pulse", "Last Stand", "Fortification", "Reconstruction", "Charges"), names(WorkshopTab.SHIELD))
        assertEquals(9, Upgrade.ofTab(WorkshopTab.LEDGER).size)
        assertEquals(listOf("Bounty Gain", "Tribute", "Interest Per Wave", "Free Assault", "Free Shield", "Free Ledger",
            "Enemy Degrade Chance", "Recovery Amount", "Max Recovery"), names(WorkshopTab.LEDGER))
        assertEquals(listOf("ASSAULT", "SHIELD", "LEDGER"), WorkshopTab.entries.map { it.label })
        val banned = listOf("Utility", "Defense tab", "Coins Per Kill", "Cash Bonus", "Enemy Level Skip",
            // retired Shield labels
            "Health Regen", "Defense %", "Defense Absolute", "Thorns", "Lifesteal", "Knockback", "Orbs", "Shockwave",
            "Death Defy", "Wall Health", "Wall Rebuild", "Land Mines")
        fun String.has(b: String) = Regex("\\b" + Regex.escape(b) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(this)
        for (u in Upgrade.entries) {
            for (b in banned) {
                assertFalse("${u.name} label $b", u.label.has(b))
                assertFalse("${u.name} desc $b", u.description.has(b))
                for (l in listOf(0, 1, u.maxLevel)) assertFalse("${u.name} format $b", u.format(l).has(b))
            }
            assertFalse(u.label == "Health")
        }
        for (r in ResearchProject.entries) for (b in banned + "Wall Plating") assertFalse(r.label.has(b))
        // currency is "Bounty" everywhere: no user-facing "coin"
        for (u in Upgrade.entries) for (t in listOf(u.label, u.description, u.format(1))) assertFalse(t, t.has("coin") || t.has("coins"))
        for (r in ResearchProject.entries) assertFalse(r.label.has("coin") || r.description.has("coin") || r.description.has("coins"))
    }

    @Test fun percentagesAreCapped() {
        for (u in Upgrade.entries) {
            val v = u.value(u.maxLevel); assertTrue(u.name, v.isFinite())
            assertTrue(u.name, u.value(u.maxLevel + 500).isFinite())
        }
        assertEquals(0.25, Upgrade.FREE_ASSAULT.value(1000), 1e-9)
        assertEquals(0.5, Upgrade.ENEMY_DEGRADE.value(1000), 1e-9)
        assertEquals(0.3, Upgrade.LAST_STAND.value(1000), 1e-9)
        assertEquals(0.05, Upgrade.DRAIN.value(1000), 1e-9)
        assertEquals(1.0, Upgrade.MAX_RECOVERY.value(1000), 1e-9)
        assertEquals(4, Upgrade.orbCount(1000))
        assertTrue(Upgrade.BARRIER.value(10_000) <= 0.75)
    }

    @Test fun assaultMaxLevelsAndCaps() {
        val expect = mapOf(Upgrade.DAMAGE to 10_000, Upgrade.ATTACK_SPEED to 1000, Upgrade.CRIT_CHANCE to 90, Upgrade.CRIT_FACTOR to 175,
            Upgrade.RANGE to 90, Upgrade.MULTISHOT to 110, Upgrade.RAPID_FIRE to 60, Upgrade.BOUNCE_SHOT to 60,
            Upgrade.SUPER_CRIT to 95, Upgrade.REND_ARMOR to 60)
        for ((u, m) in expect) assertEquals(u.name, m, u.maxLevel)
        assertEquals(40.0, Upgrade.ATTACK_SPEED.value(1000), 1e-9)
        assertEquals(1.0 + 0.039 * 500, Upgrade.ATTACK_SPEED.value(500), 1e-9)
        assertEquals(0.90, Upgrade.CRIT_CHANCE.value(90), 1e-9); assertEquals(0.89, Upgrade.CRIT_CHANCE.value(89), 1e-9)
        assertEquals(1.5 + 0.08 * 175, Upgrade.CRIT_FACTOR.value(175), 1e-9)
        assertEquals(2.4, Upgrade.RANGE.value(90), 1e-9); assertTrue(Upgrade.RANGE.value(89) < 2.4)
        assertEquals(0.5, Upgrade.MULTISHOT.value(110), 1e-9); assertEquals(3.0, Upgrade.MULTISHOT.secondary(110), 0.0)
        assertTrue(Upgrade.MULTISHOT.value(109) < 0.5)
        assertEquals(0.25, Upgrade.RAPID_FIRE.value(60), 1e-9); assertEquals(3.0, Upgrade.RAPID_FIRE.secondary(60), 1e-9)
        assertEquals(0.5, Upgrade.BOUNCE_SHOT.value(60), 1e-9); assertEquals(3.0, Upgrade.BOUNCE_SHOT.secondary(60), 0.0)
        assertEquals(0.4, Upgrade.SUPER_CRIT.value(95), 1e-9); assertTrue(Upgrade.SUPER_CRIT.value(94) < 0.4)
        assertEquals(0.2, Upgrade.REND_ARMOR.value(60), 1e-9); assertEquals(2.0, Upgrade.REND_ARMOR.secondary(60), 1e-9)
        // Damage keeps 25 x 1.14^L bounty (15 x 1.16^L cash) exactly where specified, finite to 10,000
        for (l in listOf(0, 100, 1000, 5000)) assertEquals(kotlin.math.floor(25 * Math.pow(1.14, l.toDouble())), Upgrade.DAMAGE.bountyCost(l), 0.0)
        for (l in listOf(0, 100, 2500)) assertEquals(kotlin.math.floor(15 * Math.pow(1.16, l.toDouble())), Upgrade.DAMAGE.cashCost(l), 0.0)
        assertTrue(Upgrade.DAMAGE.bountyCost(10_000).isFinite() && Upgrade.DAMAGE.cashCost(10_000).isFinite())
        assertTrue(Upgrade.DAMAGE.value(10_000).isFinite())
    }

    @Test fun highFireRateFiresSeveralShotsPerStep() {
        val s = SaveData().apply { workshop[Upgrade.ATTACK_SPEED.name] = 1000; workshop[Upgrade.DAMAGE.name] = 0 }
        assertEquals(40.0, TowerStats.resolve(s).fireRate, 1e-9)
        val e = GameEngine(s, emptyMap(), 1.0, seed = 7, godMode = true)
        val m = e.spawnMob(MobShape.ELLIPSE, 0.5f, e.towerY - 0.6f); m.hp = 1e300
        repeat(600) { e.step() }                               // 10 s
        assertTrue("shots=${e.shotsFired}", e.shotsFired in 380..420)
        // with research on top the per-step loop still keeps up (60/s ceiling -> 1 shot per step)
        val r = SaveData().apply { workshop[Upgrade.ATTACK_SPEED.name] = 1000; research[ResearchProject.LAB_ATTACK_SPEED.name] = ResearchProject.LAB_ATTACK_SPEED.maxLevel }
        assertTrue(TowerStats.resolve(r).fireRate <= TowerStats.MAX_FIRE_RATE)
    }

    @Test fun hullIntegrityCurve() {
        val u = Upgrade.HULL_INTEGRITY
        assertEquals(10_000, u.maxLevel)
        assertEquals(12.0, u.value(0), 1e-12)
        assertEquals(1.24e7, u.value(1000), 1.24e7 * 0.02)
        assertEquals(3.14e14, u.value(10_000), 3.14e14 * 0.02)
        var prev = -1.0
        for (l in 0..u.maxLevel) { val v = u.value(l); assertTrue("L$l", v > prev && v.isFinite()); prev = v }
        // relative gain shrinks through the middle, then accelerates past L5000
        fun rel(l: Int) = u.value(l + 1) / u.value(l)
        assertTrue(rel(100) > rel(1000)); assertTrue(rel(1000) > rel(6900)); assertTrue(rel(8000) > rel(5000)); assertTrue(rel(9500) > rel(8000))
        for (l in 0..u.maxLevel step 50) assertTrue(u.bountyCost(l).isFinite() && u.cashCost(l).isFinite())
        assertEquals(kotlin.math.floor(20 * Math.pow(1.13, 5000.0)), u.bountyCost(5000), 0.0)
        // Hull Integrity Research still composes multiplicatively
        val s = SaveData().apply { workshop[u.name] = 100; research[ResearchProject.LAB_HEALTH.name] = 5 }
        assertEquals(u.value(100) * ResearchProject.LAB_HEALTH.effect(5), TowerStats.resolve(s).maxHealth, 1e-6)
    }

    @Test fun coreRepairIsProportionalToHull() {
        val u = Upgrade.CORE_REPAIR
        assertEquals(10_000, u.maxLevel)
        assertEquals(0.0, u.value(0), 0.0)
        for (l in listOf(1, 50, 1000, 5000, 7000, 10_000))
            assertEquals(0.01 * Upgrade.HULL_INTEGRITY.value(l), u.value(l), u.value(l) * 1e-12)
        var prev = -1.0
        for (l in 0..u.maxLevel) { val v = u.value(l); assertTrue(v > prev && v.isFinite()); prev = v }
        for (l in 0..u.maxLevel step 50) assertTrue(u.bountyCost(l).isFinite() && u.cashCost(l).isFinite())
        assertEquals(kotlin.math.floor(30 * Math.pow(1.15, 4500.0)), u.bountyCost(4500), 0.0)
        assertTrue(u.format(1000).contains("/s") && u.format(1000).endsWith("(max 2.8%/s)"))
        // research / perk / enhancement scale regen exactly like max health
        val base = SaveData().apply { workshop[u.name] = 200; workshop[Upgrade.HULL_INTEGRITY.name] = 200 }
        val boosted = SaveData().apply { workshop[u.name] = 200; workshop[Upgrade.HULL_INTEGRITY.name] = 200; research[ResearchProject.LAB_HEALTH.name] = 10 }
        val a = TowerStats.resolve(base); val b = TowerStats.resolve(boosted)
        assertEquals(a.maxHealth * 0.01, a.regenPerSec, 1e-9)
        assertEquals(b.regenPerSec / a.regenPerSec, b.maxHealth / a.maxHealth, 1e-9)
        // regen is flat HP/s in the engine
        val e = GameEngine(base, emptyMap(), 1.0, seed = 1, godMode = false)
        e.hurtTower(e.maxHealth * 0.5, piercing = true)
        val h = e.health
        repeat(60) { e.step() }
        assertTrue(e.health - h >= a.regenPerSec * 0.95)
        assertEquals(a.regenPerSec, a.effectiveRegen(), 0.0)                 // 1% < 2.8% ceiling
        // ceiling: high Core Repair on a low hull is capped at 2.8% of max health per second
        val capped = SaveData().apply { workshop[u.name] = 2000 }
        val c = TowerStats.resolve(capped)
        assertTrue(c.regenPerSec > 0.028 * c.maxHealth)
        assertEquals(0.028 * c.maxHealth, c.effectiveRegen(), 1e-12)
        val ec = GameEngine(capped, emptyMap(), 1.0, seed = 1)
        ec.hurtTower(ec.maxHealth * 0.9, piercing = true)
        val hc = ec.health
        repeat(60) { ec.step() }
        assertEquals(0.028 * ec.maxHealth, ec.health - hc, 0.028 * ec.maxHealth * 0.02)
    }

    @Test fun platingCurve() {
        val u = Upgrade.PLATING
        assertEquals(10_000, u.maxLevel)
        assertEquals(0.0, u.value(0), 0.0)
        assertEquals(2.15e7, u.value(6000), 2.15e7 * 0.05)
        var prev = -1.0
        for (l in 0..u.maxLevel) { val v = u.value(l); assertTrue("L$l", v > prev && v.isFinite()); prev = v }
        fun rel(l: Int) = u.value(l + 1) / u.value(l)
        assertTrue(rel(100) > rel(1000)); assertTrue(rel(1000) > rel(4900)); assertTrue(rel(8000) > rel(5000))
        for (l in 0..u.maxLevel step 50) assertTrue(u.bountyCost(l).isFinite() && u.cashCost(l).isFinite())
        assertEquals(kotlin.math.floor(35 * Math.pow(1.15, 4500.0)), u.bountyCost(4500), 0.0)
        assertEquals(kotlin.math.floor(25 * Math.pow(1.18, 2500.0)), u.cashCost(2500), 0.0)
    }

    @Test fun permanentAndRunLevelsStack() {
        val s = SaveData().apply { bounty = 1e6; workshop[Upgrade.DAMAGE.name] = 10 }
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
        // permanent purchase with bounty
        assertTrue(Workshop.buy(Upgrade.HULL_INTEGRITY, s, false))
        assertEquals(1, s.level(Upgrade.HULL_INTEGRITY))
        assertTrue(s.bounty < 1e6)
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
        repeat(100) { assertTrue(e.buyRunUpgrade(Upgrade.HULL_INTEGRITY) != BuyResult.FREE) }
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
        assertEquals(full.bounty, low.bounty, 1e-9)                  // rewards stay at the current wave
        val none = GameEngine(SaveData(), emptyMap(), 1.0, startWave = 100, seed = 3)
        assertTrue((1..200).all { none.spawnMob(MobShape.ELLIPSE).statWave == 100 })
    }

    @Test fun bountyAndTributeInPipelines() {
        val s = SaveData().apply { workshop[Upgrade.BOUNTY_GAIN.name] = 10; workshop[Upgrade.TRIBUTE.name] = 20 }
        val e = GameEngine(s, emptyMap(), bountyMultiplier = 3.0, seed = 1)
        val m = e.spawnMob(MobShape.ELLIPSE, 0.5f, 0.5f)
        val cash0 = e.cash
        e.hit(m, 1e12)
        assertEquals(m.bounty * 3.0 * 1.3, e.runBounty, 1e-9)
        assertEquals(m.cash * 1.6, e.cash - cash0, 1e-9)
    }

    @Test fun lastStand() {
        fun survivals(level: Int): Int {
            var n = 0
            repeat(1000) { seed ->
                val s = SaveData().apply { workshop[Upgrade.LAST_STAND.name] = level }
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
        val s = SaveData().apply { workshop[Upgrade.FORTIFICATION.name] = 10 }   // wall = 50% of max HP
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

    @Test fun platingAndCounterfire() {
        val s = SaveData().apply { workshop[Upgrade.PLATING.name] = 10; workshop[Upgrade.COUNTERFIRE.name] = 50 }
        val e = GameEngine(s, emptyMap(), 1.0, seed = 1)
        val abs = Upgrade.PLATING.value(10)
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
