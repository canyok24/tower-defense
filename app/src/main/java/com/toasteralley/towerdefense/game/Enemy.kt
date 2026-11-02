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
    var hitFlash = 0f

    val isSlowed: Boolean get() = slowTimer > 0f

    fun applySlow(factor: Float, duration: Float) {
        if (factor >= 1f || duration <= 0f) return
        // Keep the strongest slow; refresh duration.
        slowFactor = minOf(if (isSlowed) slowFactor else 1f, factor)
        slowTimer = max(slowTimer, duration)
    }

    /** Returns true if the enemy was killed by this hit. */
    fun takeDamage(amount: Float): Boolean {
        if (!alive) return false
        hp -= max(1f, amount - type.armor)
        hitFlash = 0.08f
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
}
