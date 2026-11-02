package com.toasteralley.towerdefense.game

import com.toasteralley.towerdefense.meta.Progression
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Summary of a finished run, used to award Tokens and lifetime gold. */
data class RunResult(
    val victory: Boolean,
    val wavesCleared: Int,
    val kills: Int,
    val bossKills: Int,
    /** All gold income this run (kills, wave clears, early-start bonus; not sell refunds). */
    val goldEarned: Int,
    val tokens: Int,
)

/**
 * Pure game state + simulation. No Android view code here, so it is easy to
 * reason about and test. All access must be synchronized on the engine
 * instance (the render thread and UI thread both touch it).
 *
 * Time: the simulation always advances in fixed steps of [STEP] seconds.
 * [advance] converts real frame time x [speedMultiplier] into a whole number
 * of fixed steps (with an accumulator), so 10x speed means 10 normal steps per
 * frame, never one 10x larger step.
 */
class GameEngine(
    private val events: Events = Events.NONE,
    modifiers: RunModifiers = RunModifiers.NONE,
) {

    /** Hooks for sound / haptics / UI. Called from the game thread. */
    interface Events {
        fun onTowerPlaced() {}
        fun onTowerSold() {}
        fun onTowerUpgraded() {}
        fun onEnemyKilled(type: EnemyType) {}
        fun onLifeLost() {}
        fun onWaveStarted(wave: Int) {}
        fun onGameOver(victory: Boolean, wave: Int) {}
        /** Called exactly once per run: on game over, victory, restart or quit. */
        fun onRunFinished(result: RunResult) {}

        companion object {
            val NONE = object : Events {}
        }
    }

    enum class State { BETWEEN_WAVES, WAVE_ACTIVE, GAME_OVER, VICTORY }

    /** Global meta/prestige bonuses. Takes effect for new towers and on [reset]. */
    var modifiers: RunModifiers = modifiers
        set(value) {
            field = value
            towers.forEach { it.modifiers = value }
        }

    val map = GameMap.default()
    val towers = mutableListOf<Tower>()
    val enemies = mutableListOf<Enemy>()
    val projectiles = mutableListOf<Projectile>()
    val effects = mutableListOf<Effect>()

    var gold = WaveDefinitions.STARTING_GOLD + modifiers.bonusStartGold
        internal set
    var lives = WaveDefinitions.STARTING_LIVES + modifiers.bonusLives
        private set
    var wave = 0
        private set
    val totalWaves = WaveDefinitions.TOTAL_WAVES

    var state = State.BETWEEN_WAVES
        private set
    var paused = false
    /** Simulation speed; one of [SPEED_OPTIONS]. */
    var speedMultiplier = 1
        set(value) { field = if (value in SPEED_OPTIONS) value else 1 }
    /** Seconds until the next wave auto-starts. */
    var countdown = WaveDefinitions.FIRST_WAVE_COUNTDOWN
        private set
    var premiumUnlocked = false

    /** Transient status message shown in the HUD. */
    var message: String? = null
        private set
    private var messageTimer = 0f

    // ---- run statistics
    var kills = 0
        private set
    var bossKills = 0
        private set
    var goldEarned = 0
        private set
    var wavesCleared = 0
        private set
    /** Set once the run is finished; holds Tokens earned for the overlay. */
    var runResult: RunResult? = null
        private set

    // ---- visual-only state (never read by the simulation)
    /** True at >= [LOW_FX_SPEED]: fewer particles, no flash/shake. */
    val lowFx: Boolean get() = speedMultiplier >= LOW_FX_SPEED
    var shakeTimer = 0f
        private set
    var flashTimer = 0f
        private set
    /** Fixed steps actually simulated during the last [advance] call. */
    var lastSubsteps = 0
        private set

    private val spawnQueue = ArrayDeque<SpawnEntry>()
    private var spawnTimer = 0f
    /** Pending simulation time, measured in fixed steps. */
    private var accumulator = 0.0
    private val scratch = ArrayList<Enemy>()

    val isRunning: Boolean get() = state == State.BETWEEN_WAVES || state == State.WAVE_ACTIVE

    fun reset() {
        towers.clear(); enemies.clear(); projectiles.clear(); effects.clear(); spawnQueue.clear()
        gold = WaveDefinitions.STARTING_GOLD + modifiers.bonusStartGold
        lives = WaveDefinitions.STARTING_LIVES + modifiers.bonusLives
        wave = 0
        state = State.BETWEEN_WAVES
        countdown = WaveDefinitions.FIRST_WAVE_COUNTDOWN
        paused = false
        speedMultiplier = 1
        message = null
        kills = 0; bossKills = 0; goldEarned = 0; wavesCleared = 0
        runResult = null
        accumulator = 0.0
        shakeTimer = 0f; flashTimer = 0f
    }

    fun showMessage(text: String, seconds: Float = 2f) {
        message = text
        messageTimer = seconds
    }

    private fun earn(amount: Int) {
        gold += amount
        goldEarned += amount
    }

    // ---------------------------------------------------------------- run end

    /**
     * Ends the run (idempotent) and reports the [RunResult] once. Called
     * automatically on game over / victory; the UI calls it on restart/quit.
     * Returns null if nothing happened in this run.
     */
    fun finishRun(): RunResult? {
        runResult?.let { return it }
        if (wave == 0 && kills == 0 && goldEarned == 0) return null
        val victory = state == State.VICTORY
        val result = RunResult(
            victory = victory,
            wavesCleared = wavesCleared,
            kills = kills,
            bossKills = bossKills,
            goldEarned = goldEarned,
            tokens = Progression.tokensForRun(wavesCleared, kills, bossKills, victory),
        )
        runResult = result
        events.onRunFinished(result)
        return result
    }

    // ---------------------------------------------------------------- building

    fun towerAt(col: Int, row: Int): Tower? = towers.firstOrNull { it.col == col && it.row == row }

    fun canBuildOn(col: Int, row: Int) =
        map.inBounds(col, row) && !map.isPath(col, row) && towerAt(col, row) == null

    fun isTypeAvailable(type: TowerType) = !type.premium || premiumUnlocked

    fun placeTower(type: TowerType, col: Int, row: Int): Boolean {
        if (!isRunning) return false
        if (!isTypeAvailable(type)) { showMessage("${type.displayName} is a premium tower - unlock it in the Store"); return false }
        if (!canBuildOn(col, row)) { showMessage("Can't build there"); return false }
        if (gold < type.cost) { showMessage("Not enough gold"); return false }
        gold -= type.cost
        towers += Tower(type, col, row, modifiers)
        events.onTowerPlaced()
        return true
    }

    /** Upgrades [tower] by up to [levels] levels, paying the exact sum. */
    fun upgradeTower(tower: Tower, levels: Int = 1): Boolean {
        if (!isRunning || levels <= 0) return false
        if (tower.isMaxLevel) { showMessage("Already at max level"); return false }
        val n = levels.coerceAtMost(Tower.MAX_LEVEL - tower.level)
        val cost = tower.bulkCost(n)
        if (gold < cost) { showMessage("Not enough gold"); return false }
        repeat(n) {
            val c = tower.upgradeCost
            gold -= c
            tower.applyUpgrade(c)
        }
        addEffect(Effect(Effect.Kind.RING, tower.x, tower.y, 0.8f, 0xFFFFD54F.toInt(), 0.4f))
        if (tower.level == 10 || tower.level == 20) {
            showMessage("${tower.type.displayName} reached milestone level ${tower.level}!", 1.5f)
        }
        events.onTowerUpgraded()
        return true
    }

    /** Buys as many levels as current gold allows. */
    fun upgradeTowerMax(tower: Tower): Boolean {
        val n = tower.maxAffordable(gold)
        if (n == 0) {
            showMessage(if (tower.isMaxLevel) "Already at max level" else "Not enough gold")
            return false
        }
        return upgradeTower(tower, n)
    }

    fun sellTower(tower: Tower) {
        if (!isRunning) return
        if (towers.remove(tower)) {
            val value = tower.sellValue
            gold += value
            addEffect(Effect(Effect.Kind.TEXT, tower.x, tower.y, 0f, 0xFFFFD54F.toInt(), 0.9f, "+$value"))
            events.onTowerSold()
        }
    }

    // ---------------------------------------------------------------- waves

    fun startNextWave() {
        if (state != State.BETWEEN_WAVES) return
        // Reward starting early.
        val early = countdown.toInt()
        if (wave > 0 && early > 0) {
            earn(early)
            showMessage("Early start bonus +$early gold", 1.5f)
        }
        wave++
        spawnQueue.clear()
        spawnQueue.addAll(WaveDefinitions.build(wave))
        spawnTimer = 0.5f
        state = State.WAVE_ACTIVE
        events.onWaveStarted(wave)
    }

    private fun spawn(type: EnemyType) {
        val start = map.waypoints.first()
        enemies += Enemy(type, type.baseHp * WaveDefinitions.hpMultiplier(wave), start.x, start.y)
    }

    // ---------------------------------------------------------------- time

    /** Cycles 1x -> 2x -> ... -> 14x -> 1x. */
    fun cycleSpeed() {
        val i = SPEED_OPTIONS.indexOf(speedMultiplier)
        speedMultiplier = SPEED_OPTIONS[(i + 1) % SPEED_OPTIONS.size]
    }

    /**
     * Advances the game by one rendered frame of [realDt] seconds at the
     * current speed. Runs `realDt * speed / STEP` fixed steps (fractions are
     * carried in an accumulator). At most [maxSubsteps] steps run per frame;
     * extra time is dropped, so a slow device runs below the target speed
     * instead of using a larger, less accurate dt. Returns steps simulated.
     */
    fun advance(realDt: Float, maxSubsteps: Int = MAX_SUBSTEPS_PER_FRAME): Int {
        if (shakeTimer > 0f) shakeTimer -= realDt
        if (flashTimer > 0f) flashTimer -= realDt
        if (paused || !isRunning) {
            accumulator = 0.0
            lastSubsteps = 0
            if (!paused) updateEffects(realDt)
            return 0
        }
        accumulator += realDt.coerceIn(0f, MAX_FRAME_DT).toDouble() * speedMultiplier / STEP
        var n = floor(accumulator + 1e-6).toInt()
        if (n > maxSubsteps) {
            n = maxSubsteps
            accumulator = n.toDouble()
        }
        accumulator = (accumulator - n).coerceAtLeast(0.0)
        var done = 0
        while (done < n && isRunning && !paused) {
            update(STEP)
            done++
        }
        lastSubsteps = done
        return done
    }

    // ---------------------------------------------------------------- update

    /** One simulation step. Callers should pass [STEP]; [advance] always does. */
    fun update(dt: Float) {
        if (paused || !isRunning) {
            // Still let effects fade when the game is over.
            if (!paused) updateEffects(dt)
            return
        }
        if (messageTimer > 0f) {
            messageTimer -= dt
            if (messageTimer <= 0f) message = null
        }

        when (state) {
            State.BETWEEN_WAVES -> {
                countdown -= dt
                if (countdown <= 0f) { countdown = 0f; startNextWave() }
            }
            State.WAVE_ACTIVE -> updateSpawning(dt)
            else -> Unit
        }

        updateEnemies(dt)
        if (!isRunning) return
        updateTowers(dt)
        updateProjectiles(dt)
        updateEffects(dt)
        checkWaveEnd()
    }

    private fun updateSpawning(dt: Float) {
        if (spawnQueue.isEmpty()) return
        spawnTimer -= dt
        while (spawnTimer <= 0f && spawnQueue.isNotEmpty()) {
            val entry = spawnQueue.removeFirst()
            spawn(entry.type)
            spawnTimer += spawnQueue.firstOrNull()?.delay ?: 0f
        }
    }

    private fun updateEnemies(dt: Float) {
        val it = enemies.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.update(dt, map.waypoints)
            if (e.reachedEnd) {
                it.remove()
                lives -= e.type.damageToBase
                events.onLifeLost()
                if (lives <= 0) {
                    lives = 0
                    state = State.GAME_OVER
                    events.onGameOver(false, wave)
                    finishRun()
                    return
                }
            } else if (!e.alive) {
                it.remove()
            }
        }
    }

    private fun updateTowers(dt: Float) {
        for (t in towers) {
            if (t.beamTimer > 0f) t.beamTimer -= dt
            if (t.pulseTimer > 0f) t.pulseTimer -= dt
            when (t.type.attack) {
                Attack.BEAM -> updateBeam(t, dt)
                Attack.PULSE -> updatePulse(t, dt)
                else -> {
                    t.cooldown -= dt
                    val target = findTarget(t) ?: continue
                    t.angle = atan2(target.y - t.y, target.x - t.x)
                    if (t.cooldown > 0f) continue
                    t.cooldown = 1f / t.fireRate
                    fire(t, target)
                }
            }
        }
    }

    /** Targets the enemy furthest along the path within range ("first"). */
    private fun findTarget(t: Tower): Enemy? {
        val r2 = t.range * t.range
        var best: Enemy? = null
        for (e in enemies) {
            if (!e.alive) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            if (dx * dx + dy * dy <= r2 && (best == null || e.progress > best.progress)) best = e
        }
        return best
    }

    /**
     * Laser: continuous DoT. The main beam hits the "first" enemy for full
     * DPS; branch beams jump from the main target to the nearest other enemies
     * (in tower range and within [BEAM_BRANCH_RANGE] of the main target) for
     * [TowerStats.beamSecondary] of the DPS, up to [TowerStats.beamTargets]
     * targets in total (hard cap [TowerStats.MAX_BEAM_TARGETS]).
     */
    private fun updateBeam(t: Tower, dt: Float) {
        t.beamTargets.clear()
        val main = findTarget(t) ?: return
        t.angle = atan2(main.y - t.y, main.x - t.x)
        val s = t.stats
        val maxTargets = s.beamTargets.coerceIn(1, TowerStats.MAX_BEAM_TARGETS)
        t.beamTargets += main
        if (maxTargets > 1) {
            scratch.clear()
            val r2 = s.range * s.range
            val b2 = BEAM_BRANCH_RANGE * BEAM_BRANCH_RANGE
            for (e in enemies) {
                if (!e.alive || e === main) continue
                val tx = e.x - t.x; val ty = e.y - t.y
                val mx = e.x - main.x; val my = e.y - main.y
                if (tx * tx + ty * ty <= r2 && mx * mx + my * my <= b2) scratch += e
            }
            scratch.sortBy { (it.x - main.x) * (it.x - main.x) + (it.y - main.y) * (it.y - main.y) }
            for (i in 0 until minOf(maxTargets - 1, scratch.size)) t.beamTargets += scratch[i]
        }
        val dmg = s.damage * dt
        for ((i, e) in t.beamTargets.withIndex()) {
            val amount = if (i == 0) dmg else dmg * s.beamSecondary
            if (e.takeDotDamage(amount)) onKilled(e)
        }
    }

    /** Disruptor: when ready and an enemy is in range, stun everything in range. */
    private fun updatePulse(t: Tower, dt: Float) {
        t.cooldown -= dt
        if (t.cooldown > 0f) return
        val s = t.stats
        val r2 = s.range * s.range
        var any = false
        for (e in enemies) {
            if (!e.alive) continue
            val dx = e.x - t.x; val dy = e.y - t.y
            if (dx * dx + dy * dy <= r2) { e.applyStun(s.stunDuration); any = true }
        }
        if (!any) { t.cooldown = 0f; return }
        t.cooldown = s.stunCooldown
        t.pulseTimer = 0.35f
        addEffect(Effect(Effect.Kind.PULSE, t.x, t.y, s.range, t.type.color, 0.35f))
    }

    private fun fire(t: Tower, target: Enemy) {
        val type = t.type
        val s = t.stats
        if (type.attack == Attack.INSTANT) {
            t.beamTimer = 0.08f
            t.beamX = target.x
            t.beamY = target.y
            hitEnemy(target, s.damage, s.slowFactor, s.slowDuration, s.bossBonus)
        } else {
            projectiles += Projectile(
                t.x, t.y, target, s.damage, type.projectileSpeed, s.splashRadius,
                s.slowFactor, s.slowDuration, type.color, s.bossBonus, type
            )
        }
    }

    private fun hitEnemy(e: Enemy, damage: Float, slowFactor: Float, slowDuration: Float, bossBonus: Float = 1f) {
        if (!e.alive) return
        e.applySlow(slowFactor, slowDuration)
        val dmg = if (e.type.heavyTarget) damage * bossBonus else damage
        if (e.takeDamage(dmg, flash = !lowFx)) onKilled(e)
    }

    private fun onKilled(e: Enemy) {
        val reward = (e.type.reward * modifiers.killGoldMult).roundToInt()
        earn(reward)
        kills++
        if (e.type == EnemyType.BOSS) bossKills++
        if (!lowFx) addEffect(Effect(Effect.Kind.TEXT, e.x, e.y, 0f, 0xFFFFD54F.toInt(), 0.7f, "+$reward"))
        addEffect(Effect(Effect.Kind.RING, e.x, e.y, e.type.radius * 1.6f, e.type.color, 0.3f))
        events.onEnemyKilled(e.type)
    }

    private fun updateProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.target.alive) {
                p.targetX = p.target.x
                p.targetY = p.target.y
            }
            val dx = p.targetX - p.x
            val dy = p.targetY - p.y
            val dist = sqrt(dx * dx + dy * dy)
            val step = p.speed * dt
            if (dist <= step) {
                impact(p)
                it.remove()
            } else {
                p.x += dx / dist * step
                p.y += dy / dist * step
            }
        }
    }

    private fun impact(p: Projectile) {
        if (p.splashRadius > 0f) {
            val r2 = p.splashRadius * p.splashRadius
            for (i in enemies.indices) {
                val e = enemies[i]
                val dx = e.x - p.targetX
                val dy = e.y - p.targetY
                if (dx * dx + dy * dy <= r2) hitEnemy(e, p.damage, p.slowFactor, p.slowDuration, p.bossBonus)
            }
            if (p.source == TowerType.ANNIHILATOR) {
                addEffect(Effect(Effect.Kind.BLAST, p.targetX, p.targetY, p.splashRadius, p.color, 0.6f))
                if (!lowFx) { shakeTimer = 0.35f; flashTimer = 0.12f }
            } else {
                addEffect(Effect(Effect.Kind.RING, p.targetX, p.targetY, p.splashRadius, p.color, 0.3f))
            }
        } else if (p.target.alive) {
            hitEnemy(p.target, p.damage, p.slowFactor, p.slowDuration, p.bossBonus)
        }
    }

    /** Adds a visual effect unless the (speed-dependent) cap is reached. */
    private fun addEffect(fx: Effect) {
        val cap = if (lowFx) MAX_EFFECTS_LOW_FX else MAX_EFFECTS
        if (effects.size < cap) effects += fx
    }

    private fun updateEffects(dt: Float) {
        val it = effects.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.time += dt
            if (e.done) it.remove()
        }
    }

    private fun checkWaveEnd() {
        if (state != State.WAVE_ACTIVE) return
        if (spawnQueue.isNotEmpty() || enemies.isNotEmpty()) return
        val bonus = WaveDefinitions.clearBonus(wave)
        earn(bonus)
        wavesCleared = wave
        if (wave >= totalWaves) {
            state = State.VICTORY
            events.onGameOver(true, wave)
            finishRun()
        } else {
            state = State.BETWEEN_WAVES
            countdown = WaveDefinitions.BETWEEN_WAVE_COUNTDOWN
            showMessage("Wave $wave cleared! +$bonus gold")
        }
    }

    companion object {
        /** Fixed simulation step (seconds). */
        const val STEP = 1f / 60f
        /** Speed selector options. */
        val SPEED_OPTIONS = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14)
        const val MAX_SUBSTEPS_PER_FRAME = 16
        /** Real frame time is clamped to this (e.g. after a hitch). */
        const val MAX_FRAME_DT = 0.1f
        const val LOW_FX_SPEED = 10
        const val MAX_EFFECTS = 160
        const val MAX_EFFECTS_LOW_FX = 24
        const val BEAM_BRANCH_RANGE = 2.2f
    }
}
