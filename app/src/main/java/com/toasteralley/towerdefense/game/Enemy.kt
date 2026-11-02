package com.toasteralley.towerdefense.game

import kotlin.math.max
import kotlin.math.sqrt

class Enemy(val type: EnemyType, val maxHp: Float, startX: Float, startY: Float) {
    var hp = maxHp
    var x = startX
    var y = startY
    /** Index of the waypoint the enemy is currently walking towards. */
    var nextWaypoint = 1
    var alive = true
    var reachedEnd = false
    /** Total distance travelled along the path; used for "first" targeting. */
    var progress = 0f
    private var slowTimer = 0f
    private var slowFactor = 1f
    /** Remaining stun time; a stunned enemy does not move. */
    var stunTimer = 0f
        private set
    /** Short immunity after a stun ends so stacked Disruptors can't stun-lock forever. */
    var stunImmunity = 0f
        private set
    /** Visual only. */
    var hitFlash = 0f

    val isSlowed: Boolean get() = slowTimer > 0f
    val isStunned: Boolean get() = stunTimer > 0f
    val currentSlowFactor: Float get() = if (isSlowed) slowFactor else 1f

    fun applySlow(factor: Float, duration: Float) {
        if (factor >= 1f || duration <= 0f) return
        // Keep the strongest slow; refresh duration.
        slowFactor = minOf(if (isSlowed) slowFactor else 1f, factor)
        slowTimer = max(slowTimer, duration)
    }

    /**
     * Stuns for [duration] seconds scaled by the enemy's [EnemyType.stunMultiplier]
     * (bosses are stunned for a fraction of the time). Never shortens a stun.
     */
    fun applyStun(duration: Float) {
        if (!alive || duration <= 0f || stunImmunity > 0f) return
        stunTimer = max(stunTimer, duration * type.stunMultiplier)
    }

    /** Returns true if the enemy was killed by this hit. Armor applies (min 1). */
    fun takeDamage(amount: Float, flash: Boolean = true): Boolean =
        applyRawDamage(max(1f, amount - type.armor), flash)

    /** Damage-over-time (beams): armor is ignored and there is no minimum. */
    fun takeDotDamage(amount: Float, flash: Boolean = false): Boolean = applyRawDamage(amount, flash)

    private fun applyRawDamage(amount: Float, flash: Boolean): Boolean {
        if (!alive) return false
        hp -= amount
        if (flash) hitFlash = 0.08f
        if (hp <= 0f) {
            hp = 0f
            alive = false
            return true
        }
        return false
    }

    fun update(dt: Float, waypoints: List<Waypoint>) {
        if (!alive || reachedEnd) return
        if (hitFlash > 0f) hitFlash -= dt
        if (slowTimer > 0f) {
            slowTimer -= dt
            if (slowTimer <= 0f) slowFactor = 1f
        }
        if (stunImmunity > 0f) stunImmunity -= dt
        if (stunTimer > 0f) {
            stunTimer -= dt
            if (stunTimer <= 0f) { stunTimer = 0f; stunImmunity = STUN_IMMUNITY }
            return
        }
        var remaining = type.speed * slowFactor * dt
        while (remaining > 0f && nextWaypoint < waypoints.size) {
            val wp = waypoints[nextWaypoint]
            val dx = wp.x - x
            val dy = wp.y - y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= remaining) {
                x = wp.x
                y = wp.y
                remaining -= dist
                progress += dist
                nextWaypoint++
            } else {
                x += dx / dist * remaining
                y += dy / dist * remaining
                progress += remaining
                remaining = 0f
            }
        }
        if (nextWaypoint >= waypoints.size) reachedEnd = true
    }

    companion object {
        const val STUN_IMMUNITY = 0.5f
    }
}
