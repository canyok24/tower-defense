package com.toasteralley.emberwatch.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
    val bounty: Double,
    val cash: Double,
    val isBoss: Boolean = false,
    val isChild: Boolean = false,
    /** Wave whose stats this mob uses (lower than the current wave when degraded). */
    val statWave: Int = 0,
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
    /** Rend Armor shred: extra damage taken, e.g. 0.3 = +30%. */
    var rend = 0.0
    var orbCooldown = 0f
}

class Shot(var x: Float, var y: Float, var target: Mob?, val damage: Double, val crit: Int) {
    var vx = 0f; var vy = -1f; var alive = true
}

class EnemyShot(var x: Float, var y: Float, val vx: Float, val vy: Float, val damage: Double, val piercing: Boolean, val source: Mob?) {
    var alive = true
}

class Mine(val x: Float, val y: Float) { var alive = true }

/** Ultimate Weapon objects (rendered as simple primitives). */
class DeathWave(var y: Float, var stored: Double) { var alive = true; val hit = HashSet<Mob>() }
class Missile(var x: Float, var y: Float, var target: Mob?, val damage: Double) { var vx = 0f; var vy = -1f; var alive = true }
class UwMine(val x: Float, val y: Float, var charges: Int) { var alive = true }

enum class FxKind { BEAM, NOVA, CHAIN, POP, BOSS_WARN, SHOCK, FREE, LAST_STAND, GOLDEN }
class Fx(val kind: FxKind, val x: Float, val y: Float, val x2: Float, val y2: Float, val maxLife: Float, val color: Int = 0) {
    var life = maxLife
}

/** Where damage came from: only TOWER hits bounce; TOWER/BOUNCE hits rend, steal life and knock back. */
enum class HitSource { TOWER, BOUNCE, OTHER }

/** Result of trying to buy an in-run (cash) workshop level. */
enum class BuyResult { BOUGHT, FREE, NO_CASH, MAXED, LOCKED }

/**
 * Pure-Kotlin simulation (no Android). World units: playfield width = 1,
 * height = [worldH] (set from the view's aspect ratio). Fixed 1/60 s steps;
 * speed-ups run more steps per frame, never a bigger dt.
 *
 * [save] is read-only here: permanent workshop levels, perks and research come
 * from it; in-run (cash) workshop levels live in [runLevels] and reset each run.
 */
class GameEngine(
    val save: SaveData,
    /** Ultimate Weapons (Stones): unlocked set, stat values and Plus abilities. */
    val ultimates: UltimateLoadout = UltimateLoadout.from(save),
    /** Packs x boost x research x enhancement x stage (Bounty Gain is applied separately). */
    val bountyMultiplier: Double = 1.0,
    val coreMultiplier: Double = 1.0,
    val stage: Int = 1,
    /** Enhancement level: decides the normal-mob pool (see EnemyPool). */
    val enhancementLevel: Int = save.enhancement,
    startWave: Int = 1,
    seed: Long = System.nanoTime(),
    var godMode: Boolean = false,
    val bypassGates: Boolean = false,
) {
    companion object {
        const val STEP = 1f / 60f
        const val MAX_SHOTS_PER_STEP = 4
        /**
         * Hull Integrity starts at 12 (was 100), so all enemy damage is scaled by 0.12 to keep the early
         * game identical. Counterfire reflects in pre-scale units and Drain heals in post-scale units, so
         * both keep their old balance relative to enemy HP and tower HP.
         */
        const val ENEMY_DAMAGE_SCALE = 0.12
        val SPEEDS = intArrayOf(1, 2, 3, 5)
        const val MAX_MOBS = 220
        const val MAX_FX = 120
        const val MAX_MINES = 6
        const val TOWER_RADIUS = 0.07f
        const val WAVE_SPAWN_SECONDS = 16f
        const val WAVE_OVERTIME = 10f
        const val ORBIT_RADIUS = 0.17f
        const val ORB_RADIUS = 0.022f
        const val SHOCK_RADIUS = 0.5f
        const val MINE_RADIUS = 0.03f
        const val MINE_SPLASH = 0.12f
        const val BOUNCE_RANGE = 0.35f
        const val WALL_OFFSET = 0.14f
        // Ultimate Weapons
        const val CHRONO_RADIUS = 0.6f
        const val SWAMP_RADIUS = 0.18f
        const val BEAM_TURN = 0.8f              // rad/s
        const val CHAIN_RANGE = 0.4f
        const val BLACK_HOLE_PULL = 0.35f       // world units/s
        const val BLACK_HOLE_DPS = 5.0          // x tower damage per second
        const val DEATH_WAVE_SPEED = 0.6f       // x worldH per second
        const val DEATH_WAVE_SPACING = 0.5f
        const val DEATH_WAVE_STORE = 0.25       // fraction of a victim's max HP stored per kill
        const val MISSILE_SPEED = 0.9f
        const val MISSILE_SPLASH = 0.1f
        const val UW_MINE_RING = 0.25f
        const val UW_MINE_RADIUS = 0.03f
        const val UW_MINE_SPLASH = 0.12f
    }

    private val rng = Random(seed)
    val stageDef = StageDefinitions.get(stage)
    var worldH = 2.1f
        set(v) { field = v.coerceIn(1.2f, 3f) }
    val towerX = 0.5f
    val towerY get() = worldH - 0.16f
    /** Line where contact mobs hit the tower. */
    val towerLineY get() = towerY - TOWER_RADIUS - 0.02f
    /** Line of the Wall (Shield tab) above the tower. */
    val wallLineY get() = towerLineY - WALL_OFFSET
    /** Backwards-compatible name: the line contact mobs currently stop at. */
    val wallY get() = if (wallUp) wallLineY else towerLineY

    val mobs = ArrayList<Mob>()
    val shots = ArrayList<Shot>()
    val enemyShots = ArrayList<EnemyShot>()
    val mines = ArrayList<Mine>()
    val fx = ArrayList<Fx>()

    // tower state
    val runLevels = IntArray(Upgrade.entries.size)
    var stats: TowerStats = TowerStats.resolve(save, runLevels); private set
    val maxHealth get() = stats.maxHealth
    var health = stats.maxHealth; private set
    var cash = stats.startingCash; private set
    private var fireCooldown = 0f
    private var rapidTimer = 0f
    var wallHp = stats.wallMaxHp; private set
    private var wallDownTimer = 0f
    val wallUp get() = stats.wallMaxHp > 0 && wallHp > 0
    private var shockTimer = 0f
    private var mineTimer = 0f
    var deathDefies = 0; private set
    var freePurchases = 0; private set
    /** Tower shots fired this run (for tests / stats). */
    var shotsFired = 0L; private set

    // wave state
    var wave = startWave.coerceAtLeast(1); private set
    private var toSpawn = 0
    private var spawnTimer = 0f
    private var spawnInterval = 1f
    private var overtime = 0f
    private var bossPending = false
    var bossAlive = false; private set

    // ultimates
    val uwCooldown = HashMap<Ultimate, Float>()
    var goldenTimer = 0f; private set
    var blackHoleTimer = 0f; private set
    var blackHoleX = 0.5f; private set
    var blackHoleY = 0.5f; private set
    var chronoTimer = 0f; private set
    var swampTimer = 0f; private set
    var swampX = 0.5f; private set
    var swampY = 0.5f; private set
    private var swampTick = 0f
    private var beamTick = 0f
    private var pendingDeathWaves = 0
    private var deathWaveDelay = 0f
    val deathWaves = ArrayList<DeathWave>()
    val missiles = ArrayList<Missile>()
    val uwMines = ArrayList<UwMine>()
    /** Stone events raised during the run (boss kills); the host drains and applies them. */
    val stoneEvents = ArrayList<GameEvent>()
    var bossKills = 0; private set

    // results
    var runBounty = 0.0; private set
    var runDiamonds = 0L; private set
    var runCores = 0L; private set
    var kills = 0; private set
    var gameOver = false; private set
    var paused = false
    var speedIndex = 0
    var time = 0f; private set
    private var accumulator = 0f

    val speed get() = SPEEDS[speedIndex]
    val rapidActive get() = rapidTimer > 0f

    init {
        for (uw in ultimates.unlocked) cooldownOf(uw)?.let { uwCooldown[uw] = it * 0.5f }
        shockTimer = stats.shockPeriod.toFloat()
        mineTimer = stats.mineInterval.toFloat()
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
        updateDefenses(dt)
        updateUltimates(dt)
        updateFx(dt)
        mobs.removeAll { !it.alive }
        mines.removeAll { !it.alive }
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
                if (mobs.size < MAX_MOBS) spawnMob(WaveRules.pickShape(wave, rng, enhancementLevel))
            }
            return
        }
        overtime += dt
        // Waves are timed: an unkilled boss carries over instead of stalling the run.
        if (mobs.isEmpty() || overtime >= WAVE_OVERTIME) completeWave()
    }

    private fun completeWave() {
        earnBounty(WaveRules.bountyScale(wave) * 4)
        earnCash(WaveRules.cashScale(wave) * 3)
        // Interest Per Wave: on held cash, capped at a multiple of the wave's base cash.
        if (stats.interest > 0) cash += minOf(cash * stats.interest, WaveRules.cashScale(wave) * stats.interestCapWaveCash)
        // Recovery Amount, capped by Max Recovery.
        val heal = minOf(stats.recoveryAmount, stats.maxRecovery) * maxHealth
        if (heal > 0) health = minOf(maxHealth, health + heal)
        if (wave % 5 == 0) runCores += (1 * coreMultiplier).toLong().coerceAtLeast(1)
        startWave(wave + 1)
    }

    /** Bounty pipeline: base x bountyMultiplier (packs, boost, research, enhancement, stage) x Bounty Gain. */
    private fun earnBounty(base: Double) { runBounty += base * bountyMultiplier * stats.bountyGain * goldenMult() }
    /** Cash pipeline: base x Tribute. */
    private fun earnCash(base: Double) { cash += base * stats.tribute * goldenMult() }

    /**
     * Enemy Degrade: with chance stats.degradeChance a spawning enemy uses the
     * stats of 1..degradeMaxWaves earlier waves (never below wave 1). Rewards
     * still use the current wave.
     */
    fun rollStatWave(): Int {
        if (stats.degradeChance <= 0 || rng.nextDouble() >= stats.degradeChance) return wave
        return (wave - (1 + rng.nextInt(stats.degradeMaxWaves.coerceAtLeast(1)))).coerceAtLeast(1)
    }

    fun spawnMob(shape: MobShape, x: Float = 0.08f + rng.nextFloat() * 0.84f, y: Float? = null, child: Boolean = false): Mob {
        val sw = rollStatWave()
        val prof = WaveRules.veteran(MobProfiles.of(shape), sw).let { if (child) it.copy(splits = false) else it }
        val look = MobLook.random(shape, rng).let { if (child) it.copy(size = SizeTier.SMALL) else it }
        val hp = WaveRules.hpScale(sw) * prof.hp * stageDef.hpMult * (if (child) 0.35 else 1.0)
        val m = Mob(
            look = look, profile = prof, maxHp = hp,
            damage = WaveRules.damageScale(sw) * prof.damage * stageDef.damageMult * ENEMY_DAMAGE_SCALE,
            x = x, y = y ?: -0.05f,
            speed = prof.speed * WaveRules.speedScale(sw) * stageDef.speedMult.toFloat() * worldH,
            radius = prof.radius * look.size.scale,
            bounty = WaveRules.bountyScale(wave) * prof.bounty * (if (child) 0.3 else 1.0),
            cash = WaveRules.cashScale(wave) * prof.bounty * (if (child) 0.3 else 1.0),
            isChild = child,
            statWave = sw,
        )
        m.age = rng.nextFloat() * 10f
        mobs += m
        return m
    }

    fun spawnBoss(): Mob {
        val prof = MobProfiles.of(MobShape.SPIRAL)
        val look = MobLook(MobShape.SPIRAL, 0, OutlineStyle.GLOW, SizeTier.LARGE, rotationSpeed = 0.8f, pulsing = true, innerShape = MobShape.YIN_YANG.ordinal)
        val hp = WaveRules.hpScale(wave) * prof.hp * stageDef.hpMult * (1 + wave / 100.0)
        val m = Mob(
            look, prof, hp, WaveRules.damageScale(wave) * prof.damage * stageDef.damageMult * ENEMY_DAMAGE_SCALE,
            x = 0.5f, y = -0.1f, speed = prof.speed * worldH, radius = prof.radius,
            bounty = WaveRules.bossReward(wave), cash = WaveRules.cashScale(wave) * 25, isBoss = true, statWave = wave,
        )
        mobs += m
        bossAlive = true
        addFx(Fx(FxKind.BOSS_WARN, 0.5f, 0f, 0f, 0f, 2f))
        return m
    }

    // ------------------------------------------------------------------ mobs
    private fun updateMobs(dt: Float) {
        val standoffY = worldH * 0.42f
        val contactLine = wallY
        for (m in mobs) {
            if (!m.alive) continue
            m.age += dt
            if (m.hitFlash > 0) m.hitFlash -= dt
            if (m.orbCooldown > 0) m.orbCooldown -= dt
            if (m.profile.regen > 0 && m.hp < m.maxHp) m.hp = (m.hp + m.maxHp * m.profile.regen * dt).coerceAtMost(m.maxHp)
            val stopY = if (m.profile.ranged) standoffY else contactLine - m.radius
            // Wall fell: mobs that were pressing on it resume their descent.
            if (m.engaged && !m.profile.ranged && m.y < stopY - 0.005f) m.engaged = false
            if (!m.engaged) {
                var v = m.speed * slowFactor(m)
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
                m.attackTimer -= dt * slowFactor(m)
                if (m.attackTimer <= 0f) {
                    if (m.profile.ranged) {
                        m.attackTimer = 2.2f
                        val dx = towerX - m.x; val dy = towerY - m.y
                        val d = hypot(dx, dy).coerceAtLeast(0.01f)
                        val s = 0.55f * worldH
                        enemyShots += EnemyShot(m.x, m.y, dx / d * s, dy / d * s, m.damage, m.profile.damageType == DamageType.PIERCING, m)
                    } else {
                        m.attackTimer = if (m.isBoss) 1.5f else 1f
                        hurtTower(m.damage, m.profile.damageType == DamageType.PIERCING, m)
                    }
                }
            }
        }
    }

    /**
     * Damage to the tower. Order: Defense % (halved vs piercing) -> Defense Absolute
     * (at least 10% of the post-% hit gets through) -> Thorns reflect (of the raw hit)
     * -> Wall absorbs first -> Death Defy roll on a lethal hit -> health.
     */
    fun hurtTower(raw: Double, piercing: Boolean, attacker: Mob? = null) {
        if (godMode) return
        val def = if (piercing) stats.defense / 2 else stats.defense
        val afterPct = raw * (1 - def)
        var d = maxOf(afterPct * 0.1, afterPct - stats.defenseAbs)
        if (attacker != null && attacker.alive && stats.thorns > 0) hit(attacker, raw / ENEMY_DAMAGE_SCALE * stats.thorns, HitSource.OTHER)
        if (wallUp) {
            val a = minOf(wallHp, d)
            wallHp -= a; d -= a
            if (wallHp <= 0) { wallHp = 0.0; wallDownTimer = stats.wallRebuildSec.toFloat() }
        }
        if (d <= 0) return
        if (health - d <= 0 && stats.deathDefy > 0 && rng.nextDouble() < stats.deathDefy) {
            health = maxOf(1.0, maxHealth * 0.01)
            deathDefies++
            addFx(Fx(FxKind.LAST_STAND, towerX, towerY, 0f, 0f, 0.8f))
            return
        }
        health -= d
    }

    // ------------------------------------------------------------------ tower
    val effectiveDamage get() = stats.damage
    val effectiveFireRate get() = stats.fireRate * (if (rapidActive) stats.rapidMult else 1.0)

    private fun inRange(m: Mob) = hypot(m.x - towerX, m.y - towerY) <= stats.range + m.radius

    private fun pickTarget(exclude: Collection<Mob> = emptyList()): Mob? {
        var best: Mob? = null
        for (m in mobs) if (m.alive && m.y > -m.radius && inRange(m) && m !in exclude && (best == null || m.y > best.y)) best = m
        return best
    }

    private fun updateTower(dt: Float) {
        if (rapidTimer > 0) rapidTimer -= dt
        if (stats.regenPerSec > 0 && health < maxHealth) health = minOf(maxHealth, health + stats.effectiveRegen(maxHealth) * dt)
        fireCooldown -= dt
        // Several shots per 1/60 s step at high rates (40/s workshop, x3 Rapid Fire, research):
        // up to MAX_SHOTS_PER_STEP per step, i.e. 240 shots/s max at 1x speed.
        var fired = 0
        while (fireCooldown <= 0f && fired < MAX_SHOTS_PER_STEP) {
            val target = pickTarget() ?: break
            fireCooldown += (1f / effectiveFireRate.toFloat()).coerceAtLeast(STEP / MAX_SHOTS_PER_STEP)
            fired++
            if (stats.rapidChance > 0 && !rapidActive && rng.nextDouble() < stats.rapidChance) rapidTimer = TowerStats.RAPID_FIRE_SECONDS
            val targets = arrayListOf(target)
            if (stats.multishotChance > 0 && rng.nextDouble() < stats.multishotChance) {
                repeat(stats.multishotTargets) { pickTarget(targets)?.let { targets += it } }
            }
            for (t in targets) {
                val (dmg, crit) = rollDamage(effectiveDamage)
                if (stats.lightSpeed) {
                    if (fired == 1) addFx(Fx(FxKind.BEAM, towerX, towerY - TOWER_RADIUS, t.x, t.y, 0.08f, crit))
                    hit(t, dmg, HitSource.TOWER)
                } else {
                    shots += Shot(towerX, towerY - TOWER_RADIUS, t, dmg, crit)
                }
            }
        }
        if (fireCooldown < -STEP) fireCooldown = 0f
        shotsFired += fired
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
                if (d <= sp * dt + t.radius) { hit(t, s.damage, HitSource.TOWER); s.alive = false; continue }
                s.vx = dx / d; s.vy = dy / d
            } else {
                s.target = null
                for (m in mobs) if (m.alive && abs(m.x - s.x) < m.radius && abs(m.y - s.y) < m.radius) {
                    hit(m, s.damage, HitSource.TOWER); s.alive = false; break
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
            val hitWall = wallUp && e.y >= wallLineY
            if (hitWall || hypot(e.x - towerX, e.y - towerY) < TOWER_RADIUS) { hurtTower(e.damage, e.piercing, e.source); e.alive = false }
            else if (e.y > worldH + 0.1f) e.alive = false
        }
        enemyShots.removeAll { !it.alive }
    }

    fun hit(m: Mob, dmg: Double, source: HitSource = HitSource.OTHER) {
        if (!m.alive) return
        var d = dmg * (1 + m.rend) * chronoVulnerability(m)
        val dealt = minOf(d, m.hp + m.shield)
        if (m.shield > 0) { val a = minOf(m.shield, d); m.shield -= a; d -= a }
        m.hp -= d
        m.hitFlash = 0.06f
        if (source != HitSource.OTHER) {
            if (stats.rendPerHit > 0) m.rend = minOf(stats.rendMax, m.rend + stats.rendPerHit)
            if (stats.lifesteal > 0) health = minOf(maxHealth, health + dealt * stats.lifesteal * ENEMY_DAMAGE_SCALE)
            if (stats.knockbackChance > 0 && m.hp > 0 && rng.nextDouble() < stats.knockbackChance) knockBack(m, stats.knockbackForce.toFloat())
        }
        if (m.hp <= 0) kill(m)
        if (source == HitSource.TOWER && stats.bounceChance > 0 && rng.nextDouble() < stats.bounceChance) bounce(m, dmg)
        if (source == HitSource.TOWER) chainLightning(m)
    }

    private fun knockBack(m: Mob, force: Float) {
        m.y = (m.y - if (m.isBoss) force * 0.2f else force).coerceAtLeast(0.01f)
        if (!m.profile.ranged) m.engaged = false
    }

    private fun bounce(from: Mob, dmg: Double) {
        var px = from.x; var py = from.y
        val hitSet = arrayListOf(from)
        repeat(stats.bounces) {
            val next = mobs.filter { it.alive && it !in hitSet && hypot(it.x - px, it.y - py) < BOUNCE_RANGE }
                .minByOrNull { hypot(it.x - px, it.y - py) } ?: return
            addFx(Fx(FxKind.CHAIN, px, py, next.x, next.y, 0.2f))
            hitSet += next; px = next.x; py = next.y
            hit(next, dmg, HitSource.BOUNCE)
        }
    }

    private fun kill(m: Mob) {
        m.alive = false
        kills++
        earnBounty(m.bounty)
        earnCash(m.cash)
        // Golden Tower+: every kill during the effect pays +100% of its base Bounty
        if (goldenTimer > 0f && ultimates.plus(Ultimate.GOLDEN_TOWER)) earnBounty(m.bounty)
        addFx(Fx(FxKind.POP, m.x, m.y, m.radius, 0f, 0.25f, Palette.MOB_COLORS[m.look.colorIndex]))
        if (m.isBoss) {
            bossAlive = mobs.any { it.alive && it.isBoss }
            runDiamonds += 1 + wave / 50
            runCores += ((1 + wave / 20) * coreMultiplier).toLong().coerceAtLeast(1)
            bossKills++
            stoneEvents += GameEvent.BossKill(stage)
        }
        if (m.profile.splits && !m.isChild && mobs.size < MAX_MOBS - 2) {
            for (s in listOf(-1, 1)) {
                val c = spawnMob(m.look.shape, (m.x + s * 0.04f).coerceIn(0.05f, 0.95f), m.y, child = true)
                c.baseX = c.x
            }
        }
    }

    // ------------------------------------------------------------------ shield-tab defenses
    fun orbPosition(i: Int): Pair<Float, Float> {
        val a = time * 2.5f + i * (2 * Math.PI.toFloat() / stats.orbs.coerceAtLeast(1))
        return (towerX + cos(a) * ORBIT_RADIUS) to (towerY + sin(a) * ORBIT_RADIUS)
    }

    private fun updateDefenses(dt: Float) {
        // Wall rebuild / regen
        if (stats.wallMaxHp > 0) {
            if (wallHp <= 0) {
                wallDownTimer -= dt
                if (wallDownTimer <= 0) wallHp = stats.wallMaxHp
            } else if (wallHp < stats.wallMaxHp) {
                wallHp = minOf(stats.wallMaxHp, wallHp + stats.wallMaxHp * stats.wallRegenPerSec * dt)
            }
        }
        // Orbs
        for (i in 0 until stats.orbs) {
            val (ox, oy) = orbPosition(i)
            for (m in mobs) if (m.alive && m.orbCooldown <= 0 && hypot(m.x - ox, m.y - oy) < m.radius + ORB_RADIUS) {
                m.orbCooldown = 0.4f; hit(m, stats.orbDamage, HitSource.OTHER)
            }
        }
        // Shockwave
        if (stats.shockPeriod > 0) {
            shockTimer -= dt
            if (shockTimer <= 0) {
                val near = mobs.filter { it.alive && hypot(it.x - towerX, it.y - towerY) < SHOCK_RADIUS + it.radius }
                if (near.isNotEmpty()) {
                    shockTimer = stats.shockPeriod.toFloat()
                    addFx(Fx(FxKind.SHOCK, towerX, towerY, SHOCK_RADIUS, 0f, 0.4f))
                    near.forEach { hit(it, stats.shockDamage, HitSource.OTHER); if (it.alive) knockBack(it, 0.06f) }
                } else shockTimer = 0f
            }
        }
        // Land mines
        if (stats.mineInterval > 0) {
            mineTimer -= dt
            if (mineTimer <= 0) {
                mineTimer = stats.mineInterval.toFloat()
                if (mines.size < MAX_MINES) mines += Mine(0.1f + rng.nextFloat() * 0.8f, worldH * (0.35f + rng.nextFloat() * 0.3f))
            }
            for (mine in mines) {
                if (!mine.alive) continue
                if (mobs.any { it.alive && hypot(it.x - mine.x, it.y - mine.y) < it.radius + MINE_RADIUS }) {
                    mine.alive = false
                    addFx(Fx(FxKind.NOVA, mine.x, mine.y, MINE_SPLASH, 0f, 0.3f))
                    mobs.filter { it.alive && hypot(it.x - mine.x, it.y - mine.y) < MINE_SPLASH + it.radius }
                        .forEach { hit(it, stats.mineDamage, HitSource.OTHER) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ ultimates (Stones)
    /** Management override: UWs force-enabled for this run (stats at the save's levels). */
    private val debugUnlocked = HashSet<Ultimate>()
    fun has(u: Ultimate) = ultimates.has(u) || u in debugUnlocked
    private fun uv(u: Ultimate, i: Int) = ultimates.v(u, i)

    /** Cooldown of a cooldown-based UW (null for the always-on Lighthouse and per-hit Chain Lightning). */
    fun cooldownOf(u: Ultimate): Float? = when (u) {
        Ultimate.GOLDEN_TOWER, Ultimate.BLACK_HOLE, Ultimate.DEATH_WAVE, Ultimate.CHRONO_FIELD,
        Ultimate.SMART_MISSILES, Ultimate.INNER_LAND_MINES, Ultimate.POISON_SWAMP -> uv(u, 2).toFloat()
        Ultimate.LIGHTHOUSE, Ultimate.CHAIN_LIGHTNING -> null
    }

    fun goldenMult() = if (goldenTimer > 0f) uv(Ultimate.GOLDEN_TOWER, 0) else 1.0

    private fun inChrono(m: Mob) = chronoTimer > 0f && hypot(m.x - towerX, m.y - towerY) < CHRONO_RADIUS
    private fun inSwamp(m: Mob) = swampTimer > 0f && hypot(m.x - swampX, m.y - swampY) < SWAMP_RADIUS + m.radius
    /** Chrono Field slow and Poison Swamp+ slow multiply mob speed. */
    fun slowFactor(m: Mob): Float {
        var f = 1f
        if (inChrono(m)) f *= (1 - uv(Ultimate.CHRONO_FIELD, 1)).toFloat()
        if (inSwamp(m) && ultimates.plus(Ultimate.POISON_SWAMP)) f *= 0.7f
        return f
    }
    /** Chrono Field+: enemies in the field take +50% damage. */
    private fun chronoVulnerability(m: Mob) = if (inChrono(m) && ultimates.plus(Ultimate.CHRONO_FIELD)) 1.5 else 1.0

    // ---- Lighthouse: rotating beams
    val lighthouseBeams: Int get() = if (has(Ultimate.LIGHTHOUSE)) uv(Ultimate.LIGHTHOUSE, 2).toInt().coerceIn(1, 4) else 0
    val lighthouseAngleDeg: Double get() = uv(Ultimate.LIGHTHOUSE, 1)
    /** Direction (radians, 0 = up) of beam [i]. */
    fun beamDirection(i: Int): Float {
        val speed = if (ultimates.plus(Ultimate.LIGHTHOUSE)) BEAM_TURN * 2 else BEAM_TURN
        return time * speed + i * (2 * Math.PI.toFloat() / lighthouseBeams.coerceAtLeast(1))
    }
    /** True when all four beams exist and each covers the full map (angle maxed = 360 deg). */
    val lighthouseFullCoverage: Boolean get() = lighthouseBeams == 4 && lighthouseAngleDeg >= 360.0 - 1e-9
    private fun beamMult() = uv(Ultimate.LIGHTHOUSE, 0) * (if (ultimates.plus(Ultimate.LIGHTHOUSE)) 1.25 else 1.0)
    /** Multipliers of every beam lighting [m]. */
    fun beamsCovering(m: Mob): List<Double> {
        val n = lighthouseBeams
        if (n == 0) return emptyList()
        val half = Math.toRadians(lighthouseAngleDeg / 2).toFloat()
        val a = kotlin.math.atan2(m.x - towerX, towerY - m.y)   // 0 = straight up
        val out = ArrayList<Double>(n)
        for (i in 0 until n) {
            if (lighthouseAngleDeg >= 360.0) { out += beamMult(); continue }
            var d = (a - beamDirection(i)) % (2 * Math.PI.toFloat())
            if (d > Math.PI) d -= 2 * Math.PI.toFloat(); if (d < -Math.PI) d += 2 * Math.PI.toFloat()
            if (abs(d) <= half) out += beamMult()
        }
        return out
    }
    /** Lighthouse multiplier on [m] (x1 when unlit). See Ultimates.lighthouseMultiplier for stacking. */
    fun lighthouseMultiplierOn(m: Mob) = Ultimates.lighthouseMultiplier(beamsCovering(m), lighthouseFullCoverage)

    // ---- Chain Lightning: chance per tower hit
    private fun chainLightning(from: Mob) {
        if (!has(Ultimate.CHAIN_LIGHTNING)) return
        val plus = ultimates.plus(Ultimate.CHAIN_LIGHTNING)
        val chance = uv(Ultimate.CHAIN_LIGHTNING, 2) + if (plus) 0.10 else 0.0
        if (rng.nextDouble() >= chance) return
        val dmg = effectiveDamage * uv(Ultimate.CHAIN_LIGHTNING, 0) * (if (plus) 2.0 else 1.0)
        var px = from.x; var py = from.y
        val hitSet = hashSetOf(from)
        repeat(uv(Ultimate.CHAIN_LIGHTNING, 1).toInt()) {
            val next = mobs.filter { it.alive && it !in hitSet && hypot(it.x - px, it.y - py) < CHAIN_RANGE }
                .minByOrNull { hypot(it.x - px, it.y - py) } ?: return
            addFx(Fx(FxKind.CHAIN, px, py, next.x, next.y, 0.25f))
            hitSet += next; px = next.x; py = next.y
            hit(next, dmg, HitSource.OTHER)
        }
    }

    private fun updateUltimates(dt: Float) {
        if (goldenTimer > 0) goldenTimer -= dt
        if (chronoTimer > 0) chronoTimer -= dt
        val anyMob = mobs.any { it.alive && it.y > 0 }
        for (uw in ultimates.unlocked) {
            val full = cooldownOf(uw) ?: continue
            val cd = (uwCooldown[uw] ?: 0f) - dt
            uwCooldown[uw] = cd
            if (cd > 0f || !anyMob) continue
            if (trigger(uw)) uwCooldown[uw] = full
        }
        // Lighthouse: continuous beams, damage every tick
        if (lighthouseBeams > 0) {
            beamTick -= dt
            if (beamTick <= 0f) {
                beamTick += Ultimate.TICK_SECONDS
                for (m in mobs.toList()) if (m.alive && m.y > 0) {
                    val c = beamsCovering(m)
                    if (c.isNotEmpty()) hit(m, effectiveDamage * Ultimates.lighthouseMultiplier(c, lighthouseFullCoverage), HitSource.OTHER)
                }
            }
        }
        // Black Hole: pull to the point and crush
        if (blackHoleTimer > 0) {
            blackHoleTimer -= dt
            val r = uv(Ultimate.BLACK_HOLE, 0).toFloat()
            val plus = ultimates.plus(Ultimate.BLACK_HOLE)
            for (m in mobs.toList()) {
                if (!m.alive || (m.isBoss && !plus)) continue
                val dx = blackHoleX - m.x; val dy = blackHoleY - m.y; val d = hypot(dx, dy)
                if (d > r + m.radius) continue
                if (d > 0.005f) { val pull = minOf(d, BLACK_HOLE_PULL * dt); m.x += dx / d * pull; m.y += dy / d * pull }
                m.engaged = false
                hit(m, effectiveDamage * BLACK_HOLE_DPS * (if (plus) 5.0 else 1.0) * dt, HitSource.OTHER)
            }
        }
        // Death Wave
        if (pendingDeathWaves > 0) {
            deathWaveDelay -= dt
            if (deathWaveDelay <= 0f) { deathWaves += DeathWave(towerLineY, 0.0); pendingDeathWaves--; deathWaveDelay = DEATH_WAVE_SPACING }
        }
        for (w in deathWaves) {
            w.y -= DEATH_WAVE_SPEED * worldH * dt
            val dmg = effectiveDamage * uv(Ultimate.DEATH_WAVE, 0)
            for (m in mobs.toList()) if (m.alive && m !in w.hit && abs(m.y - w.y) < m.radius + 0.02f) {
                w.hit += m
                hit(m, dmg + w.stored, HitSource.OTHER)
                if (!m.alive) w.stored = minOf(Ultimates.MAX_MULT, w.stored + m.maxHp * DEATH_WAVE_STORE * (if (ultimates.plus(Ultimate.DEATH_WAVE)) 3.0 else 1.0))
            }
            if (w.y < -0.1f) w.alive = false
        }
        deathWaves.removeAll { !it.alive }
        // Smart Missiles
        val msp = MISSILE_SPEED * worldH
        for (ms in missiles) {
            val t = ms.target?.takeIf { it.alive } ?: mobs.filter { it.alive }.minByOrNull { hypot(it.x - ms.x, it.y - ms.y) }
            ms.target = t
            if (t == null) { ms.alive = false; continue }
            val dx = t.x - ms.x; val dy = t.y - ms.y; val d = hypot(dx, dy)
            if (d <= msp * dt + t.radius) {
                ms.alive = false
                if (ultimates.plus(Ultimate.SMART_MISSILES)) {
                    addFx(Fx(FxKind.NOVA, t.x, t.y, MISSILE_SPLASH, 0f, 0.25f))
                    mobs.filter { it.alive && hypot(it.x - t.x, it.y - t.y) < MISSILE_SPLASH + it.radius }.forEach { hit(it, ms.damage, HitSource.OTHER) }
                } else hit(t, ms.damage, HitSource.OTHER)
                continue
            }
            ms.vx = dx / d; ms.vy = dy / d
            ms.x += ms.vx * msp * dt; ms.y += ms.vy * msp * dt
        }
        missiles.removeAll { !it.alive }
        // Inner Land Mines
        for (mine in uwMines) {
            if (!mine.alive) continue
            val trig = mobs.firstOrNull { it.alive && hypot(it.x - mine.x, it.y - mine.y) < UW_MINE_RADIUS + it.radius } ?: continue
            addFx(Fx(FxKind.NOVA, mine.x, mine.y, UW_MINE_SPLASH, 0f, 0.3f))
            val dmg = effectiveDamage * uv(Ultimate.INNER_LAND_MINES, 0)
            mobs.filter { it.alive && hypot(it.x - mine.x, it.y - mine.y) < UW_MINE_SPLASH + it.radius }.forEach { hit(it, dmg, HitSource.OTHER) }
            if (trig.alive) knockBack(trig, 0.02f)
            if (--mine.charges <= 0) mine.alive = false
        }
        uwMines.removeAll { !it.alive }
        // Poison Swamp
        if (swampTimer > 0) {
            swampTimer -= dt; swampTick -= dt
            if (swampTick <= 0f) {
                swampTick += Ultimate.TICK_SECONDS
                val dmg = effectiveDamage * uv(Ultimate.POISON_SWAMP, 0)
                for (m in mobs.toList()) if (m.alive && inSwamp(m)) hit(m, dmg, HitSource.OTHER)
            }
        }
    }

    /** Fires a cooldown-based ultimate weapon now. Returns false if it had nothing to do. */
    fun trigger(uw: Ultimate): Boolean {
        if (!has(uw)) return false
        val front = mobs.filter { it.alive && it.y > 0 }.maxByOrNull { it.y }
        when (uw) {
            Ultimate.GOLDEN_TOWER -> { goldenTimer = uv(uw, 1).toFloat(); addFx(Fx(FxKind.GOLDEN, towerX, towerY, 0f, 0f, 0.8f)) }
            Ultimate.BLACK_HOLE -> {
                front ?: return false
                blackHoleX = front.x; blackHoleY = (front.y - 0.1f).coerceAtLeast(0.15f); blackHoleTimer = uv(uw, 1).toFloat()
            }
            Ultimate.DEATH_WAVE -> { pendingDeathWaves += uv(uw, 1).toInt(); deathWaveDelay = 0f }
            Ultimate.CHRONO_FIELD -> chronoTimer = uv(uw, 0).toFloat()
            Ultimate.SMART_MISSILES -> {
                front ?: return false
                val dmg = effectiveDamage * uv(uw, 0)
                val n = uv(uw, 1).toInt()
                repeat(n) { i -> missiles += Missile(towerX + (i - n / 2f) * 0.02f, towerY - TOWER_RADIUS, null, dmg) }
            }
            Ultimate.INNER_LAND_MINES -> {
                uwMines.clear()
                val n = uv(uw, 1).toInt(); val charges = if (ultimates.plus(uw)) 2 else 1
                for (i in 0 until n) {
                    val a = -Math.PI.toFloat() / 2 + (i - (n - 1) / 2f) * (Math.PI.toFloat() / (n + 1))
                    uwMines += UwMine(towerX + cos(a) * UW_MINE_RING, towerY + sin(a) * UW_MINE_RING, charges)
                }
            }
            Ultimate.POISON_SWAMP -> {
                front ?: return false
                swampX = front.x; swampY = front.y; swampTimer = uv(uw, 1).toFloat(); swampTick = 0f
            }
            Ultimate.LIGHTHOUSE, Ultimate.CHAIN_LIGHTNING -> return false
        }
        return true
    }

    /** Management override: fire any unlocked UW immediately and reset its cooldown. */
    fun debugTrigger(uw: Ultimate, forceUnlock: Boolean = false): Boolean {
        if (forceUnlock && !has(uw)) debugUnlocked += uw
        val ok = trigger(uw)
        if (ok) cooldownOf(uw)?.let { uwCooldown[uw] = it }
        return ok
    }

    private fun addFx(f: Fx) { if (fx.size < MAX_FX) fx += f }
    private fun updateFx(dt: Float) {
        for (f in fx) f.life -= dt
        fx.removeAll { it.life <= 0 }
    }


    // ------------------------------------------------------------------ in-run workshop levels (cash)
    fun permanentLevel(u: Upgrade) = save.level(u)
    fun runLevel(u: Upgrade) = runLevels[u.ordinal]
    fun totalLevel(u: Upgrade) = (permanentLevel(u) + runLevel(u)).coerceAtMost(u.maxLevel)
    fun runUpgradeCost(u: Upgrade) = u.cashCost(runLevel(u))
    fun isRunUpgradeOpen(u: Upgrade) = Workshop.gateOpen(u, save, bypassGates)
    fun isRunUpgradeMaxed(u: Upgrade) = permanentLevel(u) + runLevel(u) >= u.maxLevel

    /**
     * Buys one in-run level with Cash. The tab's Free Assault / Free Shield /
     * Free Ledger chance may make it free (no cash spent).
     */
    fun buyRunUpgrade(u: Upgrade): BuyResult {
        if (gameOver) return BuyResult.LOCKED
        if (!isRunUpgradeOpen(u)) return BuyResult.LOCKED
        if (isRunUpgradeMaxed(u)) return BuyResult.MAXED
        val c = runUpgradeCost(u)
        if (cash < c) return BuyResult.NO_CASH
        val free = rng.nextDouble() < (stats.freeChance[u.tab] ?: 0.0)
        if (!free) cash -= c else { freePurchases++; addFx(Fx(FxKind.FREE, towerX, towerY - 0.2f, 0f, 0f, 0.8f)) }
        runLevels[u.ordinal]++
        refreshStats()
        return if (free) BuyResult.FREE else BuyResult.BOUGHT
    }

    private fun refreshStats() {
        val oldMax = stats.maxHealth
        val oldWallMax = stats.wallMaxHp
        val oldShock = stats.shockPeriod; val oldMine = stats.mineInterval
        stats = TowerStats.resolve(save, runLevels)
        if (stats.maxHealth > oldMax) health += stats.maxHealth - oldMax
        if (stats.wallMaxHp > oldWallMax && (wallHp > 0 || oldWallMax == 0.0)) wallHp += stats.wallMaxHp - oldWallMax
        if (oldShock == 0.0 && stats.shockPeriod > 0) shockTimer = stats.shockPeriod.toFloat()
        if (oldMine == 0.0 && stats.mineInterval > 0) mineTimer = stats.mineInterval.toFloat()
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

    /** Bounty earned so far that the host hasn't banked yet. */
    private var banked = 0.0
    fun takeUnbankedBounty(): Double { val d = runBounty - banked; banked = runBounty; return d }
}
