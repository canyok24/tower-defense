package com.toasteralley.emberwatch.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class Mob(
    val look: MobLook,
    val profile: MobProfile,
    val maxHp: Double,
    val damage: Double,
    var x: Float,
    var y: Float,
    val speed: Float,          // world units / s
    val radius: Float,
    val coins: Double,
    val cash: Double,
    val isBoss: Boolean = false,
    val isChild: Boolean = false,
) {
    var hp = maxHp
    var shield = maxHp * profile.shield
    val maxShield = shield
    var baseX = x
    var age = 0f
    var attackTimer = 0f
    var engaged = false        // reached the wall / standoff line
    var alive = true
    var hitFlash = 0f
}

class Shot(var x: Float, var y: Float, var target: Mob?, val damage: Double, val crit: Int) {
    var vx = 0f; var vy = -1f; var alive = true
}

class EnemyShot(var x: Float, var y: Float, val vx: Float, val vy: Float, val damage: Double, val piercing: Boolean) {
    var alive = true
}

enum class FxKind { BEAM, NOVA, CHAIN, FROST, GRAVITY, POP, BOSS_WARN }
class Fx(val kind: FxKind, val x: Float, val y: Float, val x2: Float, val y2: Float, val maxLife: Float, val color: Int = 0) {
    var life = maxLife
}

/** In-run upgrades bought with Cash (reset each run). */
enum class RunUpgrade(val label: String, val baseCost: Double) {
    ATTACK("ATK", 15.0), RATE("RATE", 20.0), HEALTH("HP", 15.0);
    fun cost(level: Int) = kotlin.math.floor(baseCost * 1.21.pow(level))
}

/**
 * Pure-Kotlin simulation (no Android). World units: playfield width = 1,
 * height = [worldH] (set from the view's aspect ratio). Fixed 1/60 s steps;
 * speed-ups run more steps per frame, never a bigger dt.
 */
class GameEngine(
    val stats: TowerStats,
    val ultimates: Map<UltimateWeapon, Int>,
    val coinMultiplier: Double,
    val coreMultiplier: Double = 1.0,
    val tier: Int = 1,
    startWave: Int = 1,
    seed: Long = System.nanoTime(),
    var godMode: Boolean = false,
) {
    companion object {
        const val STEP = 1f / 60f
        val SPEEDS = intArrayOf(1, 2, 3, 5)
        const val MAX_MOBS = 220
        const val MAX_FX = 120
        const val TOWER_RADIUS = 0.07f
        const val WAVE_SPAWN_SECONDS = 16f
        const val WAVE_OVERTIME = 10f
    }

    private val rng = Random(seed)
    var worldH = 2.1f
        set(v) { field = v.coerceIn(1.2f, 3f) }
    val towerX = 0.5f
    val towerY get() = worldH - 0.16f
    val wallY get() = towerY - TOWER_RADIUS - 0.02f

    val mobs = ArrayList<Mob>()
    val shots = ArrayList<Shot>()
    val enemyShots = ArrayList<EnemyShot>()
    val fx = ArrayList<Fx>()

    // tower state
    val runLevels = IntArray(RunUpgrade.entries.size)
    var maxHealth = stats.maxHealth; private set
    var health = maxHealth; private set
    var cash = stats.startingCash; private set
    private var fireCooldown = 0f

    // wave state
    var wave = startWave.coerceAtLeast(1); private set
    private var toSpawn = 0
    private var spawnTimer = 0f
    private var spawnInterval = 1f
    private var overtime = 0f
    private var bossPending = false
    var bossAlive = false; private set

    // ultimates
    val uwCooldown = HashMap<UltimateWeapon, Float>()
    private var frostTimer = 0f
    private var frostSlow = 0f

    // results
    var runCoins = 0.0; private set
    var runDiamonds = 0L; private set
    var runCores = 0L; private set
    var kills = 0; private set
    var gameOver = false; private set
    var paused = false
    var speedIndex = 0
    var time = 0f; private set
    private var accumulator = 0f

    val speed get() = SPEEDS[speedIndex]

    init {
        for ((uw, lvl) in ultimates) if (lvl > 0) uwCooldown[uw] = uw.cooldown(lvl) * 0.5f
        startWave(wave)
    }

    // ------------------------------------------------------------------ stepping
    fun advance(frameDt: Float) {
        if (paused || gameOver) { accumulator = 0f; return }
        accumulator += frameDt.coerceAtMost(0.1f) * speed
        var n = 0
        while (accumulator >= STEP && n < 60) { step(); accumulator -= STEP; n++ }
        if (n == 60) accumulator = 0f
    }

    fun step() {
        if (gameOver) return
        val dt = STEP
        time += dt
        updateWave(dt)
        updateMobs(dt)
        updateTower(dt)
        updateShots(dt)
        updateEnemyShots(dt)
        updateUltimates(dt)
        updateFx(dt)
        mobs.removeAll { !it.alive }
        if (health <= 0 && !godMode) { health = 0.0; gameOver = true }
    }

    // ------------------------------------------------------------------ waves
    private fun startWave(w: Int) {
        wave = w
        toSpawn = WaveRules.mobCount(w)
        spawnInterval = WAVE_SPAWN_SECONDS / toSpawn
        spawnTimer = 0.5f
        overtime = 0f
        bossPending = WaveRules.isBossWave(w)
    }

    private fun updateWave(dt: Float) {
        if (bossPending && toSpawn <= WaveRules.mobCount(wave) / 2) { spawnBoss(); bossPending = false }
        if (toSpawn > 0) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnTimer += spawnInterval
                toSpawn--
                if (mobs.size < MAX_MOBS) spawnMob(WaveRules.pickShape(wave, rng))
            }
            return
        }
        overtime += dt
        // Waves are timed: an unkilled boss carries over instead of stalling the run.
        if (mobs.isEmpty() || overtime >= WAVE_OVERTIME) completeWave()
    }

    private fun completeWave() {
        val bonusCoins = WaveRules.coinScale(wave) * 4 * coinMultiplier
        runCoins += bonusCoins
        cash += WaveRules.cashScale(wave) * 3
        if (wave % 5 == 0) runCores += (1 * coreMultiplier).toLong().coerceAtLeast(1)
        startWave(wave + 1)
    }

    fun spawnMob(shape: MobShape, x: Float = 0.08f + rng.nextFloat() * 0.84f, y: Float? = null, child: Boolean = false): Mob {
        val prof = MobProfiles.of(shape)
        val look = MobLook.random(shape, rng).let { if (child) it.copy(size = SizeTier.SMALL) else it }
        val hp = WaveRules.hpScale(wave) * prof.hp * Tiers.hpMult(tier) * (if (child) 0.35 else 1.0)
        val m = Mob(
            look = look, profile = prof, maxHp = hp,
            damage = WaveRules.damageScale(wave) * prof.damage * Tiers.damageMult(tier),
            x = x, y = y ?: -0.05f,
            speed = prof.speed * WaveRules.speedScale(wave) * worldH,
            radius = prof.radius * look.size.scale,
            coins = WaveRules.coinScale(wave) * prof.coin * (if (child) 0.3 else 1.0),
            cash = WaveRules.cashScale(wave) * prof.coin * (if (child) 0.3 else 1.0),
            isChild = child,
        )
        m.age = rng.nextFloat() * 10f
        mobs += m
        return m
    }

    fun spawnBoss(): Mob {
        val prof = MobProfiles.of(MobShape.SPIRAL)
        val look = MobLook(MobShape.SPIRAL, 0, OutlineStyle.GLOW, SizeTier.LARGE, rotationSpeed = 0.8f, pulsing = true, innerShape = MobShape.YIN_YANG.ordinal)
        val hp = WaveRules.hpScale(wave) * prof.hp * Tiers.hpMult(tier) * (1 + wave / 100.0)
        val m = Mob(
            look, prof, hp, WaveRules.damageScale(wave) * prof.damage * Tiers.damageMult(tier),
            x = 0.5f, y = -0.1f, speed = prof.speed * worldH, radius = prof.radius,
            coins = WaveRules.bossReward(wave), cash = WaveRules.cashScale(wave) * 25, isBoss = true,
        )
        mobs += m
        bossAlive = true
        addFx(Fx(FxKind.BOSS_WARN, 0.5f, 0f, 0f, 0f, 2f))
        return m
    }

    // ------------------------------------------------------------------ mobs
    private fun updateMobs(dt: Float) {
        val slowMul = if (frostTimer > 0f) 1f - frostSlow else 1f
        val standoffY = worldH * 0.42f
        for (m in mobs) {
            if (!m.alive) continue
            m.age += dt
            if (m.hitFlash > 0) m.hitFlash -= dt
            if (m.profile.regen > 0 && m.hp < m.maxHp) m.hp = (m.hp + m.maxHp * m.profile.regen * dt).coerceAtMost(m.maxHp)
            val stopY = if (m.profile.ranged) standoffY else wallY - m.radius
            if (!m.engaged) {
                var v = m.speed * slowMul
                if (m.profile.movement == Movement.DASH_PAUSE) v *= if ((m.age % 1.2f) < 0.6f) 2.2f else 0.05f
                m.y += v * dt
                val off = when (m.profile.movement) {
                    Movement.ZIGZAG -> { val p = (m.age / 2f) % 1f; (if (p < 0.5f) p * 4 - 1 else 3 - p * 4) * 0.12f }
                    Movement.SINE_WEAVE -> sin(m.age * 2.2f) * 0.1f
                    else -> 0f
                }
                m.x = (m.baseX + off).coerceIn(m.radius, 1f - m.radius)
                if (m.y >= stopY) { m.y = stopY; m.engaged = true; m.attackTimer = 0.2f }
            } else {
                m.attackTimer -= dt * slowMul
                if (m.attackTimer <= 0f) {
                    if (m.profile.ranged) {
                        m.attackTimer = 2.2f
                        val dx = towerX - m.x; val dy = towerY - m.y
                        val d = hypot(dx, dy).coerceAtLeast(0.01f)
                        val s = 0.55f * worldH
                        enemyShots += EnemyShot(m.x, m.y, dx / d * s, dy / d * s, m.damage, m.profile.damageType == DamageType.PIERCING)
                    } else {
                        m.attackTimer = if (m.isBoss) 1.5f else 1f
                        hurtTower(m.damage, m.profile.damageType == DamageType.PIERCING)
                    }
                }
            }
        }
    }

    private fun hurtTower(raw: Double, piercing: Boolean) {
        if (godMode) return
        val def = if (piercing) stats.defense / 2 else stats.defense
        health -= raw * (1 - def)
    }

    // ------------------------------------------------------------------ tower
    val effectiveDamage get() = stats.damage * (1 + 0.15 * runLevels[RunUpgrade.ATTACK.ordinal])
    val effectiveFireRate get() = stats.fireRate * (1 + 0.06 * runLevels[RunUpgrade.RATE.ordinal])

    private fun pickTarget(): Mob? {
        var best: Mob? = null
        for (m in mobs) if (m.alive && m.y > -m.radius && (best == null || m.y > best.y)) best = m
        return best
    }

    private fun updateTower(dt: Float) {
        fireCooldown -= dt
        if (fireCooldown > 0f) return
        val target = pickTarget() ?: return
        fireCooldown += (1f / effectiveFireRate.toFloat()).coerceAtLeast(STEP * 0.25f)
        if (fireCooldown < -STEP) fireCooldown = 0f
        val (dmg, crit) = rollDamage(effectiveDamage)
        if (stats.lightSpeed) {
            addFx(Fx(FxKind.BEAM, towerX, towerY - TOWER_RADIUS, target.x, target.y, 0.08f, crit))
            hit(target, dmg)
        } else {
            shots += Shot(towerX, towerY - TOWER_RADIUS, target, dmg, crit)
        }
    }

    /** Returns damage and crit level (0 none, 1 crit, 2 super crit). */
    fun rollDamage(base: Double): Pair<Double, Int> {
        if (rng.nextDouble() >= stats.critChance) return base to 0
        val c = base * stats.critFactor
        return if (rng.nextDouble() < stats.superCritChance) c * stats.superCritFactor to 2 else c to 1
    }

    private fun updateShots(dt: Float) {
        val sp = (stats.projectileSpeed * worldH).toFloat()
        for (s in shots) {
            val t = s.target
            if (t != null && t.alive) {
                val dx = t.x - s.x; val dy = t.y - s.y
                val d = hypot(dx, dy)
                if (d <= sp * dt + t.radius) { hit(t, s.damage); s.alive = false; continue }
                s.vx = dx / d; s.vy = dy / d
            } else {
                s.target = null
                for (m in mobs) if (m.alive && abs(m.x - s.x) < m.radius && abs(m.y - s.y) < m.radius) {
                    hit(m, s.damage); s.alive = false; break
                }
            }
            s.x += s.vx * sp * dt; s.y += s.vy * sp * dt
            if (s.y < -0.2f || s.x < -0.1f || s.x > 1.1f) s.alive = false
        }
        shots.removeAll { !it.alive }
    }

    private fun updateEnemyShots(dt: Float) {
        for (e in enemyShots) {
            e.x += e.vx * dt; e.y += e.vy * dt
            if (hypot(e.x - towerX, e.y - towerY) < TOWER_RADIUS) { hurtTower(e.damage, e.piercing); e.alive = false }
            else if (e.y > worldH + 0.1f) e.alive = false
        }
        enemyShots.removeAll { !it.alive }
    }

    fun hit(m: Mob, dmg: Double) {
        if (!m.alive) return
        var d = dmg
        if (m.shield > 0) { val a = minOf(m.shield, d); m.shield -= a; d -= a }
        m.hp -= d
        m.hitFlash = 0.06f
        if (m.hp <= 0) kill(m)
    }

    private fun kill(m: Mob) {
        m.alive = false
        kills++
        runCoins += m.coins * coinMultiplier
        cash += m.cash
        addFx(Fx(FxKind.POP, m.x, m.y, m.radius, 0f, 0.25f, Palette.MOB_COLORS[m.look.colorIndex]))
        if (m.isBoss) {
            bossAlive = mobs.any { it.alive && it.isBoss }
            runDiamonds += 1 + wave / 50
            runCores += ((1 + wave / 20) * coreMultiplier).toLong().coerceAtLeast(1)
        }
        if (m.profile.splits && !m.isChild && mobs.size < MAX_MOBS - 2) {
            for (s in listOf(-1, 1)) {
                val c = spawnMob(m.look.shape, (m.x + s * 0.04f).coerceIn(0.05f, 0.95f), m.y, child = true)
                c.baseX = c.x
            }
        }
    }

    // ------------------------------------------------------------------ ultimates
    private fun updateUltimates(dt: Float) {
        if (frostTimer > 0) frostTimer -= dt
        for ((uw, lvl) in ultimates) {
            if (lvl <= 0) continue
            val cd = (uwCooldown[uw] ?: 0f) - dt
            uwCooldown[uw] = cd
            if (cd > 0f || mobs.none { it.alive && it.y > 0 }) continue
            if (trigger(uw, lvl)) uwCooldown[uw] = uw.cooldown(lvl)
        }
    }

    /** Fires an ultimate weapon. Returns false if it had no valid targets. */
    fun trigger(uw: UltimateWeapon, lvl: Int): Boolean {
        val dmg = effectiveDamage * uw.damageFactor(lvl)
        when (uw) {
            UltimateWeapon.EMBER_NOVA -> {
                val r = 0.9f
                val targets = mobs.filter { it.alive && hypot(it.x - towerX, it.y - towerY) < r }
                if (targets.isEmpty()) return false
                targets.forEach { hit(it, dmg) }
                addFx(Fx(FxKind.NOVA, towerX, towerY, r, 0f, 0.6f))
            }
            UltimateWeapon.FROST_LATTICE -> {
                frostTimer = uw.duration(lvl); frostSlow = uw.slow(lvl)
                addFx(Fx(FxKind.FROST, 0f, 0f, 0f, 0f, 0.8f))
            }
            UltimateWeapon.CHAIN_ARC -> {
                val targets = mobs.filter { it.alive && it.y > 0 }.sortedByDescending { it.y }.take(uw.chainTargets(lvl))
                if (targets.isEmpty()) return false
                var px = towerX; var py = towerY
                for (t in targets) { addFx(Fx(FxKind.CHAIN, px, py, t.x, t.y, 0.35f)); px = t.x; py = t.y; hit(t, dmg) }
            }
            UltimateWeapon.GRAVITY_WELL -> {
                val targets = mobs.filter { it.alive && it.y > 0 }
                if (targets.isEmpty()) return false
                for (t in targets) {
                    t.y = (t.y - 0.25f * worldH).coerceAtLeast(0.02f)
                    t.engaged = false
                    hit(t, dmg)
                }
                addFx(Fx(FxKind.GRAVITY, 0.5f, worldH * 0.3f, 0f, 0f, 0.7f))
            }
        }
        return true
    }

    private fun addFx(f: Fx) { if (fx.size < MAX_FX) fx += f }
    private fun updateFx(dt: Float) {
        for (f in fx) f.life -= dt
        fx.removeAll { it.life <= 0 }
    }

    val frostActive get() = frostTimer > 0

    // ------------------------------------------------------------------ run upgrades (cash)
    fun runUpgradeCost(u: RunUpgrade) = u.cost(runLevels[u.ordinal])
    fun buyRunUpgrade(u: RunUpgrade): Boolean {
        val c = runUpgradeCost(u)
        if (cash < c || gameOver) return false
        cash -= c
        runLevels[u.ordinal]++
        if (u == RunUpgrade.HEALTH) {
            val add = stats.maxHealth * 0.12
            maxHealth += add; health += add
        }
        return true
    }

    // ------------------------------------------------------------------ management override hooks
    fun debugSkipWaves(n: Int) = debugJumpToWave(wave + n)
    fun debugJumpToWave(n: Int) {
        mobs.clear(); shots.clear(); enemyShots.clear(); bossAlive = false
        startWave(n.coerceAtLeast(1))
    }
    fun debugSpawnBoss() { spawnBoss() }
    fun debugAddCash(v: Double) { cash += v }
    fun debugHeal() { health = maxHealth }

    /** Coins earned so far that the host hasn't banked yet. */
    private var banked = 0.0
    fun takeUnbankedCoins(): Double { val d = runCoins - banked; banked = runCoins; return d }

}
