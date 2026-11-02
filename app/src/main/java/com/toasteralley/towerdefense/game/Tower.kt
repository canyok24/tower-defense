package com.toasteralley.towerdefense.game

import kotlin.math.pow
import kotlin.math.roundToInt

class Tower(val type: TowerType, val col: Int, val row: Int) {
    var level = 1
        private set
    /** Total gold spent on this tower (build + upgrades); used for sell value. */
    var invested = type.cost
        private set
    var cooldown = 0f
    /** Barrel angle in radians, for rendering. */
    var angle = -Math.PI.toFloat() / 2f
    /** Remaining display time of a laser beam and its end point. */
    var beamTimer = 0f
    var beamX = 0f
    var beamY = 0f

    val x: Float get() = col + 0.5f
    val y: Float get() = row + 0.5f

    val damage: Float get() = statsDamage(level)
    val range: Float get() = statsRange(level)
    val fireRate: Float get() = statsRate(level)

    fun statsDamage(lvl: Int) = type.damage * DAMAGE_GROWTH.pow(lvl - 1)
    fun statsRange(lvl: Int) = type.range * (1f + RANGE_GROWTH * (lvl - 1))
    fun statsRate(lvl: Int) = type.fireRate * RATE_GROWTH.pow(lvl - 1)

    val isMaxLevel: Boolean get() = level >= MAX_LEVEL

    /** Cost of the next upgrade, or -1 if already at max level. */
    val upgradeCost: Int
        get() = if (isMaxLevel) -1 else (type.cost * UPGRADE_COST_FACTOR * level).roundToInt()

    val sellValue: Int get() = (invested * SELL_REFUND).roundToInt()

    fun applyUpgrade(cost: Int) {
        invested += cost
        level++
    }

    companion object {
        const val MAX_LEVEL = 4
        const val DAMAGE_GROWTH = 1.45f
        const val RANGE_GROWTH = 0.12f
        const val RATE_GROWTH = 1.15f
        const val UPGRADE_COST_FACTOR = 0.75f
        const val SELL_REFUND = 0.7f
    }
}
