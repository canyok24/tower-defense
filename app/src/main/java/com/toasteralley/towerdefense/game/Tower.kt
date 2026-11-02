package com.toasteralley.towerdefense.game

import kotlin.math.roundToInt

class Tower(
    val type: TowerType,
    val col: Int,
    val row: Int,
    mods: RunModifiers = RunModifiers.NONE,
) {
    var level = 1
        private set
    /** Total gold spent on this tower (build + upgrades); used for sell value. */
    var invested = type.cost
        private set
    var cooldown = 0f
    /** Barrel angle in radians, for rendering. */
    var angle = -Math.PI.toFloat() / 2f
    /** Remaining display time of an instant-hit beam and its end point. */
    var beamTimer = 0f
    var beamX = 0f
    var beamY = 0f
    /** Laser: current main target followed by branch targets (rendered each frame). */
    val beamTargets = ArrayList<Enemy>(TowerStats.MAX_BEAM_TARGETS)
    /** Disruptor: visual pulse timer. */
    var pulseTimer = 0f

    var modifiers: RunModifiers = mods
        set(value) { field = value; stats = TowerStats.of(type, level, value) }

    /** Current resolved stats (level curve x global modifiers). */
    var stats: TowerStats = TowerStats.of(type, level, mods)
        private set

    val x: Float get() = col + 0.5f
    val y: Float get() = row + 0.5f

    val damage: Float get() = stats.damage
    val range: Float get() = stats.range
    val fireRate: Float get() = stats.fireRate

    fun statsAt(lvl: Int): TowerStats = TowerStats.of(type, lvl, modifiers)

    val isMaxLevel: Boolean get() = level >= MAX_LEVEL

    /** Cost of the next upgrade, or -1 if already at max level. */
    val upgradeCost: Int get() = if (isMaxLevel) -1 else UpgradeCosts.costToReach(type, level + 1)

    /** Total cost of the next [count] upgrades (clamped at max level). */
    fun bulkCost(count: Int): Int = UpgradeCosts.costRange(type, level, (level + count).coerceAtMost(MAX_LEVEL))

    /** How many levels [gold] can buy in a row, starting from the current level. */
    fun maxAffordable(gold: Long): Int {
        var n = 0
        var spent = 0L
        while (level + n < MAX_LEVEL) {
            val c = UpgradeCosts.costToReach(type, level + n + 1)
            if (spent + c > gold) break
            spent += c
            n++
        }
        return n
    }

    val sellValue: Int get() = (invested * SELL_REFUND).roundToInt()

    fun applyUpgrade(cost: Int) {
        invested += cost
        level++
        stats = TowerStats.of(type, level, modifiers)
    }

    companion object {
        const val MAX_LEVEL = TowerStats.MAX_LEVEL
        const val SELL_REFUND = 0.7f
    }
}
