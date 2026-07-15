package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * korogue uses kotile's [Grid] as its single 2D grid type (ADR-0026). These are the
 * korogue-specific operations on it — the ones that lean on engine geometry
 * ([IntRect], [distanceSq]) and so sit above kotile's boundary.
 */

/**
 * Invokes [action] for every in-bounds coordinate whose Euclidean distance from [center]
 * is within [radius], visiting a bounding box clipped to the grid. [center] itself is
 * always visited when in bounds (distance 0).
 *
 * Iteration is column-major within the bounding box, matching [Grid.forEachCoordinate].
 */
fun <T> Grid<T>.forEachCoordinateInRadius(
    center: Vector2Int,
    radius: Double,
    action: (Vector2Int) -> Unit,
) {
    val boundingBox = boundingBoxForCircle(center, radius)
    val radiusSquared = radius * radius

    (boundingBox.x until boundingBox.x + boundingBox.width).forEach { x ->
        (boundingBox.y until boundingBox.y + boundingBox.height).forEach { y ->
            val coordinate = Vector2Int(x, y)

            if (center.distanceSq(coordinate) <= radiusSquared) {
                action(coordinate)
            }
        }
    }
}

/**
 * Returns a new grid holding the per-cell logical OR of this grid and [other], which must
 * have the same dimensions.
 */
infix fun Grid<Boolean>.or(other: Grid<Boolean>): Grid<Boolean> {
    val newGrid = copy()

    other.forEachCoordinate { coordinate ->
        newGrid[coordinate] = newGrid[coordinate] || other[coordinate]
    }

    return newGrid
}

private fun <T> Grid<T>.boundingBoxForCircle(
    center: Vector2Int,
    radius: Double,
): IntRect {
    val radiusInt = ceil(radius).toInt()

    val x = max(0, center.x - radiusInt)
    val y = max(0, center.y - radiusInt)
    // +1 because lastColumnIndex/lastRowIndex are inclusive indices and the
    // iteration over the box is half-open (`until x + width`); without it the
    // east/south boundary tiles at center ± radius are never visited.
    val width = min(lastColumnIndex, center.x + radiusInt) - x + 1
    val height = min(lastRowIndex, center.y + radiusInt) - y + 1

    return IntRect(x, y, width, height)
}
