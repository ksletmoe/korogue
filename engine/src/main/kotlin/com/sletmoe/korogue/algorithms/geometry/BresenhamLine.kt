package com.sletmoe.korogue.algorithms.geometry

import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.abs

/**
 * The ordered cells from [from] to [to] inclusive, via Bresenham's line algorithm — for a
 * bolt/arrow/thrown item's grid-snapped flight path (krogue-tnf). Unlike
 * [LineOfSightCalculator][com.sletmoe.korogue.algorithms.los.LineOfSightCalculator], which only
 * answers "is X visible from Y" as a boolean FOV grid, this returns the actual stepped-through
 * path between two points. Works in any octant/direction; a zero-length line ([from] == [to])
 * returns a single-cell list.
 */
fun bresenhamLine(
    from: Vector2Int,
    to: Vector2Int,
): List<Vector2Int> {
    val cells = mutableListOf<Vector2Int>()
    var x0 = from.x
    var y0 = from.y
    val dx = abs(to.x - x0)
    val dy = -abs(to.y - y0)
    val sx = if (x0 < to.x) 1 else -1
    val sy = if (y0 < to.y) 1 else -1
    var err = dx + dy
    while (true) {
        cells.add(Vector2Int(x0, y0))
        if (x0 == to.x && y0 == to.y) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x0 += sx
        }
        if (e2 <= dx) {
            err += dx
            y0 += sy
        }
    }
    return cells
}

/**
 * [bresenhamLine] from [from] to [to], truncated at (and including) the first cell for which
 * [isBlocked] returns `true` — a bolt stops where it hits something, not past it. [from] itself
 * (the shooter's own cell) is never tested. Returns the full line if nothing blocks it.
 */
fun lineOfCellsStoppingAtBlocker(
    from: Vector2Int,
    to: Vector2Int,
    isBlocked: (Vector2Int) -> Boolean,
): List<Vector2Int> {
    val line = bresenhamLine(from, to)
    val blockedOffset = line.drop(1).indexOfFirst(isBlocked)
    return if (blockedOffset == -1) line else line.take(blockedOffset + 2)
}
