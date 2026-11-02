package com.toasteralley.towerdefense.game

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure game state + simulation. No Android view code here, so it is easy to
 * reason about and test. All access must be synchronized on the engine
 * instance (the render thread and UI thread both touch it).
 */
class GameEngine(private val events: Events = Events.NONE) {

    /** Hooks for sound / haptics / UI. Called from the game thread. */
    interface Events {
        fun onTowerPlaced() {}
        fun onTowerSold() {}
        fun onTowerUpgraded() {}
        fun onEnemyKilled(type: EnemyType) {}
        fun onLifeLost() {}
        fun onWaveStarted(wave: Int) {}
        fun onGameOver(victory: Boolean, wave: Int) {}

        companion object {
            val NONE = object : Events {}
        }
    }

    enum class State { BETWEEN_WAVES, WAVE_ACTIVE, GAME_OVER, VICTORY }

    val map = GameMap.default()
    val towers = mutableListOf<Tower>()
    val enemies = mutableListOf<Enemy>()
    val projectiles = mutableListOf<Projectile>()
    val effects = mutableListOf<Effect>()

    var gold = WaveDefinitions.STARTING_GOLD
        private set
    var lives = WaveDefinitions.STARTING_LIVES
        private set
    var wave = 0
        private set
    val totalWaves = WaveDefinitions.TOTAL_WAVES

    var state = State.BETWEEN_WAVES
        private set
    var paused = false
    var speedMultiplier = 1
    /** Seconds until the next wave auto-starts. */
    var countdown = WaveDefinitions.FIRST_WAVE_COUNTDOWN
        private set
    var premiumUnlocked = false

    /** Transient status message shown in the HUD. */
    var message: String? = null
        private set
    private var messageTimer = 0f

    private val spawnQueue = ArrayDeque<SpawnEntry>()
    private var spawnTimer = 0f
    private var elapsed = 0f

    val isRunning: Boolean get() = state == State.BETWEEN_WAVES || state == State.WAVE_ACTIVE

    fun reset() {
        towers.clear(); enemies.clear(); projectiles.clear(); effects.clear(); spawnQueue.clear()
        gold = WaveDefinitions.STARTING_GOLD
        lives = WaveDefinitions.STARTING_LIVES
        wave = 0
        state = State.BETWEEN_WAVES
        countdown = WaveDefinitions.FIRST_WAVE_COUNTDOWN
        paused = false
        speedMultiplier = 1
        message = null
    }

    fun showMessage(text: String, seconds: Float = 2f) {
        message = text
        messageTimer = seconds
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
        towers += Tower(type, col, row)
        events.onTowerPlaced()
        return true
    }

    fun upgradeTower(tower: Tower): Boolean {
        if (!isRunning) return false
        if (tower.isMaxLevel) { showMessage("Already at max level"); return false }
        val cost = tower.upgradeCost
        if (gold < cost) { showMessage("Not enough gold"); return false }
        gold -= cost
        tower.applyUpgrade(cost)
        effects += Effect(Effect.Kind.RING, tower.x, tower.y, 0.8f, 0xFFFFD54F.toInt(), 0.4f)
        events.onTowerUpgraded()
        return true
    }

    fun sellTower(tower: Tower) {
        if (!isRunning) return
        if (towers.remove(tower)) {
            val value = tower.sellValue
            gold += value
            effects += Effect(Effect.Kind.TEXT, tower.x, tower.y, 0f, 0xFFFFD54F.toInt(), 0.9f, "+$value")
            events.onTowerSold()
        }
    }

    // ---------------------------------------------------------------- waves

    fun startNextWave() {
        if (state != State.BETWEEN_WAVES) return
        // Reward starting early.
        val early = countdown.toInt()
        if (wave > 0 && early > 0) {
            gold += early
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

    // ---------------------------------------------------------------- update

    fun update(dt: Float) {
        if (paused || !isRunning) {
            // Still let effects fade when the game is over.
            if (!paused) updateEffects(dt)
            return
        }
        elapsed += dt
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
            t.cooldown -= dt
            val target = findTarget(t) ?: continue
            t.angle = atan2(target.y - t.y, target.x - t.x)
            if (t.cooldown > 0f) continue
            t.cooldown = 1f / t.fireRate
            fire(t, target)
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

    private fun fire(t: Tower, target: Enemy) {
        val type = t.type
        if (type.projectileSpeed <= 0f) {
            // Instant beam.
            t.beamTimer = 0.08f
            t.beamX = target.x
            t.beamY = target.y
            hitEnemy(target, t.damage, type.slowFactor, type.slowDuration)
        } else {
            projectiles += Projectile(
                t.x, t.y, target, t.damage, type.projectileSpeed, type.splashRadius,
                type.slowFactor, type.slowDuration, type.color
            )
        }
    }

    private fun hitEnemy(e: Enemy, damage: Float, slowFactor: Float, slowDuration: Float) {
        if (!e.alive) return
        e.applySlow(slowFactor, slowDuration)
        if (e.takeDamage(damage)) {
            gold += e.type.reward
            effects += Effect(Effect.Kind.TEXT, e.x, e.y, 0f, 0xFFFFD54F.toInt(), 0.7f, "+${e.type.reward}")
            effects += Effect(Effect.Kind.RING, e.x, e.y, e.type.radius * 1.6f, e.type.color, 0.3f)
            events.onEnemyKilled(e.type)
        }
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
            // Copy: hitEnemy never mutates the list, but be defensive.
            for (e in enemies.toList()) {
                val dx = e.x - p.targetX
                val dy = e.y - p.targetY
                if (dx * dx + dy * dy <= r2) hitEnemy(e, p.damage, p.slowFactor, p.slowDuration)
            }
            effects += Effect(Effect.Kind.RING, p.targetX, p.targetY, p.splashRadius, p.color, 0.3f)
        } else if (p.target.alive) {
            hitEnemy(p.target, p.damage, p.slowFactor, p.slowDuration)
        }
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
        gold += bonus
        if (wave >= totalWaves) {
            state = State.VICTORY
            events.onGameOver(true, wave)
        } else {
            state = State.BETWEEN_WAVES
            countdown = WaveDefinitions.BETWEEN_WAVE_COUNTDOWN
            showMessage("Wave $wave cleared! +$bonus gold")
        }
    }
}
