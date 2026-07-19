package com.sletmoe.kotile.utilities

/**
 * An integer position or size on a 2D grid.
 *
 * @property x column, increasing rightwards
 * @property y row, increasing downwards
 */
data class Vector2Int(val x: Int, val y: Int) {
    /** Component-wise sum — e.g. offsetting a position by a delta (`origin + delta`). */
    operator fun plus(other: Vector2Int): Vector2Int = Vector2Int(x + other.x, y + other.y)

    /** Component-wise difference — e.g. the delta between two positions (`next - from`). */
    operator fun minus(other: Vector2Int): Vector2Int = Vector2Int(x - other.x, y - other.y)
}
