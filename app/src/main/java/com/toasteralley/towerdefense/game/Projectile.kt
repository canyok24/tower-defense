package com.toasteralley.towerdefense.game

class Projectile(
    var x: Float,
    var y: Float,
    val target: Enemy,
    val damage: Float,
    val speed: Float,
    val splashRadius: Float,
    val slowFactor: Float,
    val slowDuration: Float,
    val color: Int,
    /** Damage multiplier vs Boss/Tank (Heavy). */
    val bossBonus: Float = 1f,
    val source: TowerType = TowerType.ARROW,
) {
    var alive = true
    /** Last known target position, so shells still land if the target dies. */
    var targetX = target.x
    var targetY = target.y
}

/** Short-lived visual effect (explosions, rings, floating text). Visual only. */
class Effect(
    val kind: Kind,
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Int,
    val duration: Float,
    val text: String = "",
) {
    enum class Kind { RING, TEXT, BLAST, PULSE }
    var time = 0f
    val progress: Float get() = (time / duration).coerceIn(0f, 1f)
    val done: Boolean get() = time >= duration
}
