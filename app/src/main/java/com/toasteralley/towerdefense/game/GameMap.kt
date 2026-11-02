package com.toasteralley.towerdefense.game

import kotlin.math.sign

data class Waypoint(val x: Float, val y: Float)

/**
 * Grid map. The path is described by grid waypoints (col,row); straight
 * segments between consecutive waypoints become path tiles. Waypoints may lie
 * just outside the grid so enemies walk in from / out of the screen edge.
 */
class GameMap(val cols: Int, val rows: Int, gridWaypoints: List<Pair<Int, Int>>) {

    /** Waypoints at tile centres, in tile units. */
    val waypoints: List<Waypoint> = gridWaypoints.map { (c, r) -> Waypoint(c + 0.5f, r + 0.5f) }

    private val path = Array(rows) { BooleanArray(cols) }

    init {
        require(gridWaypoints.size >= 2) { "Path needs at least two waypoints" }
        for (i in 0 until gridWaypoints.size - 1) {
            val (c0, r0) = gridWaypoints[i]
            val (c1, r1) = gridWaypoints[i + 1]
            require(c0 == c1 || r0 == r1) { "Path segments must be horizontal or vertical" }
            val dc = (c1 - c0).sign
            val dr = (r1 - r0).sign
            var c = c0
            var r = r0
            while (true) {
                if (inBounds(c, r)) path[r][c] = true
                if (c == c1 && r == r1) break
                c += dc
                r += dr
            }
        }
    }

    fun inBounds(col: Int, row: Int) = col in 0 until cols && row in 0 until rows

    fun isPath(col: Int, row: Int) = inBounds(col, row) && path[row][col]

    companion object {
        const val COLS = 16
        const val ROWS = 9

        /** The default level. Edit these waypoints to design a new map. */
        fun default() = GameMap(
            COLS, ROWS,
            listOf(
                -1 to 1, 3 to 1, 3 to 6, 7 to 6, 7 to 2,
                11 to 2, 11 to 7, 14 to 7, 14 to 4, 16 to 4
            )
        )
    }
}
