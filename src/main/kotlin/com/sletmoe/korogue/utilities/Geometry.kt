package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.sqrt

/*
 * Geometry helpers for the migration off java.awt.Point / java.awt.Rectangle.
 *
 * korogue uses kotile's [Vector2Int] as its 2D integer coordinate type. Unlike
 * java.awt.Point, [Vector2Int] is an immutable data class, so any code that used
 * to mutate a Point in place must now reassign a new value instead.
 */

/** Squared Euclidean distance, replacing `java.awt.Point.distanceSq`. */
fun Vector2Int.distanceSq(other: Vector2Int): Double {
    val dx = (x - other.x).toDouble()
    val dy = (y - other.y).toDouble()
    return dx * dx + dy * dy
}

/** Euclidean distance, replacing `java.awt.Point.distance`. */
fun Vector2Int.distance(other: Vector2Int): Double = sqrt(distanceSq(other))

/**
 * An axis-aligned integer rectangle, replacing `java.awt.Rectangle` for the
 * bounds / bounding-box use cases in korogue.
 *
 * @property x left edge (inclusive)
 * @property y top edge (inclusive)
 * @property width number of columns
 * @property height number of rows
 */
data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    /**
     * Returns true if [point] lies within `[x, x + width)` x `[y, y + height)`,
     * matching `java.awt.Rectangle.contains(Point)` (the high edge is exclusive).
     */
    fun contains(point: Vector2Int): Boolean =
        point.x >= x && point.x < x + width && point.y >= y && point.y < y + height
}
