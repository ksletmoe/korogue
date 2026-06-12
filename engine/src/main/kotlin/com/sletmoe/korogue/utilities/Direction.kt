package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int

/**
 * The eight compass directions on the integer grid, each a unit step ([dx], [dy]) — the shared
 * vocabulary for movement, AI, field-of-view, and throwing, replacing ad-hoc `dx to dy` pairs.
 *
 * y increases downward (the screen/grid convention used throughout korogue), so [NORTH] is
 * `dy = -1`. Declared clockwise from north so [opposite] is a half-turn away.
 */
enum class Direction(
    val dx: Int,
    val dy: Int,
) {
    NORTH(0, -1),
    NORTHEAST(1, -1),
    EAST(1, 0),
    SOUTHEAST(1, 1),
    SOUTH(0, 1),
    SOUTHWEST(-1, 1),
    WEST(-1, 0),
    NORTHWEST(-1, -1),
    ;

    /** This direction reversed (a half-turn). */
    val opposite: Direction get() = entries[(ordinal + 4) % entries.size]

    /** [origin] stepped one cell in this direction. */
    fun from(origin: Vector2Int): Vector2Int = Vector2Int(origin.x + dx, origin.y + dy)

    companion object {
        /** The four orthogonal directions, in clockwise order: N, E, S, W. */
        val CARDINAL: List<Direction> = listOf(NORTH, EAST, SOUTH, WEST)

        /** The four diagonal directions, in clockwise order: NE, SE, SW, NW. */
        val DIAGONAL: List<Direction> = listOf(NORTHEAST, SOUTHEAST, SOUTHWEST, NORTHWEST)

        /**
         * The single grid step toward ([dx], [dy]) — each component clamped to `-1..1` — or `null`
         * for no movement `(0, 0)`. Turns an arbitrary delta (e.g. toward a target) into one of the
         * eight directions.
         */
        fun ofStep(
            dx: Int,
            dy: Int,
        ): Direction? {
            val sx = dx.coerceIn(-1, 1)
            val sy = dy.coerceIn(-1, 1)
            if (sx == 0 && sy == 0) return null
            return entries.first { it.dx == sx && it.dy == sy }
        }

        /** The direction from [origin] toward [target] as a single step, or `null` if they coincide. */
        fun between(
            origin: Vector2Int,
            target: Vector2Int,
        ): Direction? = ofStep(target.x - origin.x, target.y - origin.y)
    }
}
