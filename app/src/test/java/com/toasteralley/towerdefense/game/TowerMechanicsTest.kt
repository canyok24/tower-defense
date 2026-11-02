package com.toasteralley.towerdefense.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerMechanicsTest {

    private val dt = GameEngine.STEP

    private fun engine() = GameEngine().apply { premiumUnlocked = true }

    /** A frozen (long-stunned) dummy enemy so positions are stable for the test. */
    private fun dummy(type: EnemyType, x: Float, y: Float, progress: Float, hp: Float = 100_000f) =
        Enemy(type, hp, x, y).also { it.progress = progress; it.applyStun(10_000f) }

    private fun towerAt(type: TowerType, col: Int, row: Int, level: Int = 1): Tower {
        val t = Tower(type, col, row)
        while (t.level < level) t.applyUpgrade(0)
        return t
    }

    private fun run(e: GameEngine, seconds: Float) {
        repeat((seconds / dt).toInt()) { e.update(dt) }
    }

    // ------------------------------------------------------------ stun

    @Test
    fun stunnedEnemyDoesNotMoveAndRecovers() {
        val map = GameMap.default()
        val e = Enemy(EnemyType.NORMAL, 100f, map.waypoints[0].x, map.waypoints[0].y)
        e.applyStun(0.5f)
        assertTrue(e.isStunned)
        val x0 = e.x
        repeat(20) { e.update(dt, map.waypoints) } // 0.33 s
        assertEquals(x0, e.x, 0f)
        repeat(20) { e.update(dt, map.waypoints) } // 0.67 s total
        assertFalse(e.isStunned)
        repeat(10) { e.update(dt, map.waypoints) }
        assertTrue(e.x > x0)
    }

    @Test
    fun bossesAreStunnedForLess() {
        val boss = Enemy(EnemyType.BOSS, 100f, 0f, 0f)
        val grunt = Enemy(EnemyType.NORMAL, 100f, 0f, 0f)
        boss.applyStun(1f); grunt.applyStun(1f)
        assertEquals(1f, grunt.stunTimer, 1e-6f)
        assertEquals(EnemyType.BOSS.stunMultiplier, boss.stunTimer, 1e-6f)
        assertTrue(boss.stunTimer < 0.5f)
    }

    @Test
    fun disruptorStunsEnemiesInRangeAndDealsNoDamage() {
        val e = engine()
        e.towers += towerAt(TowerType.DISRUPTOR, 2, 2)
        val near = Enemy(EnemyType.NORMAL, 100f, 3.5f, 2.5f)
        val far = Enemy(EnemyType.NORMAL, 100f, 9.5f, 0.5f)
        e.enemies += near; e.enemies += far
        e.update(dt)
        assertTrue(near.isStunned)
        assertFalse(far.isStunned)
        assertEquals(100.0, near.hp, 0.0)
        // Cooldown: no second pulse until stunCooldown elapsed.
        val t = e.towers[0]
        assertEquals(t.stats.stunCooldown, t.cooldown, 1e-4f)
    }

    // ------------------------------------------------------------ laser beam

    @Test
    fun laserBeamDpsAndReducedSecondaryDamage() {
        val e = engine()
        val laser = towerAt(TowerType.LASER, 2, 2)
        e.towers += laser
        // Small HP so float precision doesn't blur the tiny per-step amounts.
        val main = dummy(EnemyType.NORMAL, 3.5f, 2.5f, progress = 10f, hp = 500f)
        val others = (0 until 5).map { dummy(EnemyType.NORMAL, 3.5f, 2.7f + it * 0.2f, progress = it.toFloat(), hp = 500f) }
        e.enemies += main; e.enemies += others
        run(e, 1f)
        val dps = laser.stats.damage
        assertEquals(TowerType.LASER.damage, dps, 1e-4f)
        assertEquals(dps.toDouble(), main.maxHp - main.hp, dps * 0.02)
        val hit = others.filter { it.hp < it.maxHp }
        // Level 1: 3 targets total -> 2 branches.
        assertEquals(laser.stats.beamTargets - 1, hit.size)
        for (o in hit) {
            val ratio = (o.maxHp - o.hp) / (main.maxHp - main.hp)
            assertEquals(laser.stats.beamSecondary.toDouble(), ratio, 1e-3)
            assertTrue(ratio in 0.29..0.41)
        }
    }

    @Test
    fun laserHitsAtMostTenTargets() {
        val e = engine()
        val laser = towerAt(TowerType.LASER, 2, 2, level = 20)
        assertEquals(10, laser.stats.beamTargets)
        e.towers += laser
        val enemies = (0 until 15).map { dummy(EnemyType.NORMAL, 3.2f + (it % 5) * 0.2f, 2.0f + (it / 5) * 0.4f, it.toFloat()) }
        e.enemies += enemies
        run(e, 0.5f)
        assertEquals(10, enemies.count { it.hp < it.maxHp })
        assertEquals(10, laser.beamTargets.size)
        // Secondary damage grows with level but stays clamped below main.
        assertTrue(laser.stats.beamSecondary <= TowerStats.MAX_BEAM_SECONDARY)
        assertTrue(laser.stats.beamSecondary > TowerType.LASER.beamSecondary)
    }

    @Test
    fun laserBranchesGrowWithLevel() {
        var prev = 0
        for (l in 1..20) {
            val n = TowerStats.beamTargetsAt(TowerType.LASER, l)
            assertTrue(n >= prev); assertTrue(n <= 10); prev = n
        }
        assertEquals(3, TowerStats.beamTargetsAt(TowerType.LASER, 1))
    }

    // ------------------------------------------------------------ slow tower

    @Test
    fun slowTowerSlowsOnlyTheTargetOnHit() {
        val e = engine()
        e.towers += towerAt(TowerType.SLOW, 2, 2)
        val target = dummy(EnemyType.NORMAL, 3.5f, 2.5f, progress = 5f, hp = 1000f)
        val bystander = dummy(EnemyType.NORMAL, 3.6f, 2.6f, progress = 1f, hp = 1000f)
        e.enemies += target; e.enemies += bystander
        run(e, 1.2f)
        assertTrue(target.isSlowed)
        assertEquals(TowerType.SLOW.slowFactor, target.currentSlowFactor, 1e-6f)
        assertEquals(TowerType.SLOW.damage.toDouble(), target.maxHp - target.hp, 1e-3)
        assertFalse(bystander.isSlowed)
        assertEquals(bystander.maxHp, bystander.hp, 0.0)
    }

    @Test
    fun slowProjectilesAreSlow() {
        assertTrue(TowerType.SLOW.projectileSpeed < TowerType.ARROW.projectileSpeed / 2)
    }

    // ------------------------------------------------------------ heavy

    @Test
    fun heavyDealsBonusDamageToBossAndTank() {
        for (type in listOf(EnemyType.BOSS, EnemyType.TANK, EnemyType.NORMAL)) {
            val e = engine()
            e.towers += towerAt(TowerType.HEAVY, 2, 2)
            val enemy = dummy(type, 3.5f, 2.5f, 1f, hp = 10_000f)
            e.enemies += enemy
            var t = 0f
            while (enemy.hp == enemy.maxHp && t < 5f) { e.update(dt); t += dt }
            val dealt = enemy.maxHp - enemy.hp
            val bonus = if (type.heavyTarget) TowerType.HEAVY.bossBonus else 1f
            assertEquals("$type", (TowerType.HEAVY.damage * bonus - type.armor).toDouble(), dealt, 1e-2)
        }
        assertTrue(TowerStats.bossBonusAt(TowerType.HEAVY, 20) > TowerStats.bossBonusAt(TowerType.HEAVY, 1))
        assertTrue(TowerStats.bossBonusAt(TowerType.HEAVY, 20) <= TowerStats.MAX_BOSS_BONUS)
    }

    // ------------------------------------------------------------ annihilator

    @Test
    fun annihilatorFireRateCurve() {
        val a = TowerType.ANNIHILATOR
        // Slow early: never faster than one shot per 2 s before the surge.
        for (l in 1..TowerStats.ANNIHILATOR_SURGE_LEVEL) {
            val interval = 1f / TowerStats.fireRateAt(a, l)
            assertTrue("L$l interval $interval", interval >= 2.0f)
            if (l <= 13) assertTrue("L$l interval $interval", interval >= 3.0f)
        }
        assertTrue(1f / TowerStats.fireRateAt(a, 1) in 3f..4f)
        // Surge 80 -> 100 down to the firm 0.4 s gap.
        assertEquals(0.4f, 1f / TowerStats.fireRateAt(a, 100), 1e-4f)
        assertTrue(1f / TowerStats.fireRateAt(a, 90) in 0.6f..1.2f)
        var prevRate = 0f
        var prevDmg = 0f
        for (l in 1..TowerStats.MAX_LEVEL) {
            val r = TowerStats.fireRateAt(a, l)
            val d = TowerStats.damageAt(a, l)
            assertTrue("rate L$l", r > prevRate); assertTrue("dmg L$l", d > prevDmg)
            if (l > TowerStats.ANNIHILATOR_SURGE_LEVEL) assertTrue("surge at L$l", r / prevRate > 1.05f)
            else if (l > 13) assertTrue("gentle at L$l", r / prevRate < 1.02f)
            prevRate = r; prevDmg = d
        }
    }

    @Test
    fun annihilatorMinimumShotGapHoldsWithModifiers() {
        val fast = RunModifiers(fireRateMult = 5f)
        for (l in 1..TowerStats.MAX_LEVEL) {
            val gap = 1f / TowerStats.of(TowerType.ANNIHILATOR, l, fast).fireRate
            assertTrue("L$l gap $gap", gap >= TowerStats.ANNIHILATOR_MIN_SHOT_GAP - 1e-6f)
        }
        assertEquals(TowerStats.ANNIHILATOR_MIN_SHOT_GAP, 1f / TowerStats.of(TowerType.ANNIHILATOR, 100, fast).fireRate, 1e-5f)
        // In the engine: a level-100 Annihilator fires at most once per 0.4 s.
        val e = engine()
        val t = towerAt(TowerType.ANNIHILATOR, 2, 2, level = 100)
        t.modifiers = fast
        e.towers += t
        e.enemies += dummy(EnemyType.BOSS, 3.5f, 2.5f, 0f, hp = 1e30f)
        var shots = 0
        var prevCd = t.cooldown
        repeat(600) { // 10 s
            e.update(1f / 60f)
            if (t.cooldown > prevCd + 0.1f) shots++
            prevCd = t.cooldown
        }
        assertTrue("shots $shots", shots in 20..26)
    }

    @Test
    fun annihilatorSplashesEverythingNearby() {
        val e = engine()
        e.towers += towerAt(TowerType.ANNIHILATOR, 2, 2)
        val group = (0 until 4).map { dummy(EnemyType.NORMAL, 3.5f + it * 0.3f, 2.5f, it.toFloat(), hp = 5000f) }
        e.enemies += group
        run(e, 2f)
        assertTrue(group.all { it.hp <= it.maxHp - TowerType.ANNIHILATOR.damage + 1f })
    }

    // ------------------------------------------------------------ levels

    @Test
    fun levelCapIsHundredAndStatsAreMonotonic() {
        assertEquals(100, Tower.MAX_LEVEL)
        assertEquals(11, TowerType.entries.size)
        for (type in TowerType.entries) {
            var prev = TowerStats.of(type, 1)
            for (l in 2..100) {
                val s = TowerStats.of(type, l)
                assertTrue("$type dmg L$l", s.damage >= prev.damage)
                assertTrue("$type range L$l", s.range >= prev.range)
                assertTrue("$type rate L$l", s.fireRate >= prev.fireRate)
                assertTrue("$type splash L$l", s.splashRadius >= prev.splashRadius)
                assertTrue("$type slow L$l", s.slowFactor <= prev.slowFactor)
                assertTrue("$type slowDur L$l", s.slowDuration >= prev.slowDuration)
                assertTrue("$type stun L$l", s.stunDuration >= prev.stunDuration)
                assertTrue("$type stunCd L$l", s.stunCooldown <= prev.stunCooldown)
                assertTrue("$type beams L$l", s.beamTargets >= prev.beamTargets)
                assertTrue("$type beam% L$l", s.beamSecondary >= prev.beamSecondary)
                assertTrue("$type boss L$l", s.bossBonus >= prev.bossBonus)
                assertTrue("$type finite L$l", s.damage.isFinite() && s.fireRate.isFinite())
                prev = s
            }
            // Milestone jumps at 10 and 20 are bigger than the regular step.
            val d9 = TowerStats.damageAt(type, 9); val d10 = TowerStats.damageAt(type, 10); val d11 = TowerStats.damageAt(type, 11)
            if (type.damage > 0f) assertTrue(d10 / d9 > d11 / d10)
        }
        // Clamped at max level.
        assertEquals(TowerStats.of(TowerType.ARROW, 100), TowerStats.of(TowerType.ARROW, 125))
    }

    @Test
    fun firmCapsHoldAtEveryLevel() {
        for (type in TowerType.entries) for (l in 1..100) {
            val s = TowerStats.of(type, l)
            assertTrue("$type range L$l", s.range <= type.range * TowerStats.MAX_RANGE_FACTOR + 1e-4f)
            assertTrue("$type abs range L$l", s.range <= maxOf(TowerStats.ABS_MAX_RANGE, type.range) + 1e-4f)
            assertTrue("$type slow L$l", s.slowFactor >= TowerStats.MIN_SLOW_FACTOR)
            assertTrue("$type slowDur L$l", s.slowDuration <= type.slowDuration * TowerStats.MAX_SLOW_DURATION_FACTOR + 1e-4f)
            assertTrue("$type stun L$l", s.stunDuration <= TowerStats.MAX_STUN)
            assertTrue("$type stunCd L$l", type.attack != Attack.PULSE || s.stunCooldown >= TowerStats.MIN_STUN_COOLDOWN)
            assertTrue("$type beams L$l", s.beamTargets <= TowerStats.MAX_BEAM_TARGETS)
            assertTrue("$type beam% L$l", s.beamSecondary <= TowerStats.MAX_BEAM_SECONDARY)
            assertTrue("$type boss L$l", s.bossBonus <= TowerStats.MAX_BOSS_BONUS)
            assertTrue("$type splash L$l", s.splashRadius <= type.splashRadius * TowerStats.MAX_SPLASH_FACTOR + 1e-4f)
            if (type.attack != Attack.PULSE && type != TowerType.ANNIHILATOR)
                assertTrue("$type rate L$l", s.fireRate <= minOf(type.fireRate * TowerStats.MAX_RATE_FACTOR, TowerStats.ABS_MAX_RATE) + 1e-4f)
        }
        val laser = TowerStats.of(TowerType.LASER, 100)
        assertEquals(10, laser.beamTargets)
        assertEquals(TowerStats.MAX_BEAM_SECONDARY, laser.beamSecondary, 1e-6f)
        val disruptor = TowerStats.of(TowerType.DISRUPTOR, 100)
        assertEquals(TowerStats.MAX_STUN, disruptor.stunDuration, 1e-6f)
        assertEquals(TowerStats.MIN_STUN_COOLDOWN, disruptor.stunCooldown, 1e-6f)
        assertEquals(TowerStats.MIN_SLOW_FACTOR, TowerStats.of(TowerType.FROST, 100).slowFactor, 1e-6f)
    }

    @Test
    fun damageGrowthFlattensPastLevel20() {
        val a = TowerType.ARROW
        for (l in 2..20) assertEquals(1.12, TowerStats.damageGrowthAt(a, l), 1e-12)
        var prev = TowerStats.damageGrowthAt(a, 21)
        assertTrue(prev < 1.12)
        for (l in 22..100) {
            val g = TowerStats.damageGrowthAt(a, l)
            assertTrue("taper L$l", g <= prev && g > TowerStats.LATE_DAMAGE_GROWTH)
            prev = g
        }
        assertTrue("L100 growth $prev", prev in 1.03..1.04)
        // Late milestones (30..100) are a bump, smaller than the L10/L20 bonuses.
        for (m in 30..100 step 10) {
            val jump = TowerStats.damageAt(a, m) / TowerStats.damageAt(a, m - 1)
            val normal = TowerStats.damageAt(a, m - 1) / TowerStats.damageAt(a, m - 2)
            assertTrue("milestone $m", jump > normal * 1.05f && jump < TowerStats.L10_DAMAGE)
        }
        // Not absurd: Arrow L100 is ~1,400x L1 (the steep 1.12 curve would give > 75,000x).
        val ratio = TowerStats.damageAt(a, 100) / TowerStats.damageAt(a, 1)
        assertTrue("ratio $ratio", ratio in 500f..3000f)
        // L1..20 unchanged.
        assertEquals(150.72f, TowerStats.damageAt(a, 20), 0.01f)
    }

    @Test
    fun cannotUpgradePastHundred() {
        val e = engine()
        e.gold = WaveDefinitions.MAX_GOLD
        assertTrue(e.placeTower(TowerType.ANNIHILATOR, 0, 0))
        val t = e.towerAt(0, 0)!!
        assertTrue(e.upgradeTowerMax(t))
        assertEquals(100, t.level)
        assertTrue(t.isMaxLevel)
        assertEquals(-1L, t.upgradeCost)
        assertFalse(e.upgradeTower(t))
        assertEquals(0, t.maxAffordable(e.gold))
        assertEquals(TowerType.ANNIHILATOR.cost + UpgradeCosts.costRange(TowerType.ANNIHILATOR, 1, 100), t.invested)
        assertTrue(t.sellValue > 0 && t.damage.isFinite())
    }

    @Test
    fun premiumSetIsCorrect() {
        assertEquals(
            setOf(TowerType.SNIPER, TowerType.RAILGUN, TowerType.LASER, TowerType.ANNIHILATOR),
            TowerType.entries.filter { it.premium }.toSet()
        )
        assertEquals(Attack.INSTANT, TowerType.RAILGUN.attack)
        assertEquals(Attack.BEAM, TowerType.LASER.attack)
        assertNotNull(TowerType.entries.map { it.color }.toSet().takeIf { it.size == 11 })
    }
}
