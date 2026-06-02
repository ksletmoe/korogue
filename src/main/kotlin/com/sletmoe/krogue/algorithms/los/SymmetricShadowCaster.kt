package com.sletmoe.krogue.algorithms.los

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.IntRect
import com.sletmoe.krogue.utilities.distanceSq
import com.sletmoe.krogue.world.Tile
import org.apache.commons.math3.fraction.Fraction
import kotlin.math.ceil
import kotlin.math.floor

/*
The SymmetricShadowCaster class in this file implements the LOS algorithm described at
https://www.albertford.com/shadowcasting, the example implementation of which is licensed under CC0
*/

private enum class CardinalDirection { NORTH, EAST, SOUTH, WEST }

// represents a coordinate relative to a Quadrant
private data class QuadrantPoint(val quadrant: Quadrant, val row: Int, val column: Int) {
    fun toGridCoordinates(): Vector2Int = quadrant.transform(this)
}

// represents a 90 degree facing, where we process each row starting at the one adjacent and perpendicular to the
// origin, moving outwards. For east and west facing quadrants, "rows" are actually vertical columns on our grid of
// tiles
private class Quadrant(val direction: CardinalDirection, val origin: Vector2Int) {
    /**
     * convert the parameter coordinates [quadrantPoint], representing a position relative to this quadrant, to an
     * absolute set of coordinates on the grid of tiles which is returned
     */
    fun transform(quadrantPoint: QuadrantPoint): Vector2Int {
        return when (direction) {
            CardinalDirection.NORTH -> Vector2Int(origin.x + quadrantPoint.column, origin.y - quadrantPoint.row)
            CardinalDirection.EAST -> Vector2Int(origin.x + quadrantPoint.row, origin.y + quadrantPoint.column)
            CardinalDirection.SOUTH -> Vector2Int(origin.x + quadrantPoint.column, origin.y + quadrantPoint.row)
            CardinalDirection.WEST -> Vector2Int(origin.x - quadrantPoint.row, origin.y + quadrantPoint.column)
        }
    }
}

/**
 * Represents a row of tiles perpendicular to the facing of a Quadrant, starting at the intersection of a line
 * from the Quadrant's origin with startSlope and a Row of tiles depth spaces away from the origin. The Row ends
 * at the intersection between a line from origin with endSlope, and that same such row of tiles.
 */
private class Row(val quadrant: Quadrant, val depth: Int, val startSlope: Fraction, val endSlope: Fraction) {
    /**
     * round [value] to the nearest whole number, rounding ties up
     */
    private fun roundTiesUp(value: Double): Int = floor(value + 0.5).toInt()

    /**
     * round [value] to the nearest whole number, rounding ties down
     */
    private fun roundTiesDown(value: Double): Int = ceil(value - 0.5).toInt()

    val minColumn: Int by lazy { roundTiesUp(startSlope.multiply(depth).toDouble()) }
    val maxColumn: Int by lazy { roundTiesDown(endSlope.multiply(depth).toDouble()) }

    fun withStartSlope(newStartSlope: Fraction): Row = Row(quadrant, depth, newStartSlope, endSlope)

    fun withEndSlope(newEndSlope: Fraction): Row = Row(quadrant, depth, startSlope, newEndSlope)

    fun forEachCoordinate(receiver: (QuadrantPoint) -> Unit) {
        (minColumn..maxColumn).forEach { column ->
            receiver(QuadrantPoint(quadrant, depth, column))
        }
    }

    fun nextRow(): Row = Row(quadrant, depth + 1, startSlope, endSlope)
}

class SymmetricShadowCaster : LineOfSightCalculator {
    override fun calculateLineOfSight(
        origin: Vector2Int,
        tiles: Grid<Tile>,
        maxViewDistance: Double?,
    ): Grid<Boolean> {
        val visibilityGrid = Grid(tiles.width, tiles.height, defaultValue = false)

        // mark the origin as visible
        visibilityGrid[origin.x, origin.y] = true

        // calculate LOS by shadow casting in each cardinal direction
        CardinalDirection.values().forEach { cardinalDirection ->
            val quadrant = Quadrant(cardinalDirection, origin)
            val firstRow = Row(quadrant, 1, Fraction.MINUS_ONE, Fraction.ONE)
            castShadows(firstRow, tiles, visibilityGrid)
        }

        if (maxViewDistance != null) {
            limitVisibilityDistance(origin, maxViewDistance, visibilityGrid)
        }

        return visibilityGrid
    }

    private fun castShadows(
        row: Row,
        tiles: Grid<Tile>,
        visibilityGrid: Grid<Boolean>,
    ) {
        val gridRectangle = IntRect(0, 0, tiles.width, tiles.height)
        var previousBlocksLineOfSight = false
        var currentRow = row

        if (!rowInBounds(currentRow, gridRectangle)) {
            return
        }

        currentRow.forEachCoordinate { quadrantPoint ->
            val tileCoordinates = quadrantPoint.toGridCoordinates()

            if (gridRectangle.contains(tileCoordinates)) {
                if (tiles[tileCoordinates].blocksLineOfSight || isSymmetric(currentRow, quadrantPoint)) {
                    visibilityGrid[tileCoordinates] = true
                }

                if (previousBlocksLineOfSight && !tiles[tileCoordinates].blocksLineOfSight) {
                    currentRow = currentRow.withStartSlope(calculateSlope(quadrantPoint))
                }

                if (
                    quadrantPoint.column != currentRow.minColumn &&
                    !previousBlocksLineOfSight &&
                    tiles[tileCoordinates].blocksLineOfSight
                ) {
                    val nextRow = currentRow.nextRow().withEndSlope(calculateSlope(quadrantPoint))
                    castShadows(nextRow, tiles, visibilityGrid)
                }

                previousBlocksLineOfSight = tiles[tileCoordinates].blocksLineOfSight
            }
        }

        if (!previousBlocksLineOfSight) {
            castShadows(currentRow.nextRow(), tiles, visibilityGrid)
        }
    }

    private fun limitVisibilityDistance(
        origin: Vector2Int,
        maxVisibilityDistance: Double,
        visibilityGrid: Grid<Boolean>,
    ) {
        val visDistanceSquared = maxVisibilityDistance * maxVisibilityDistance

        visibilityGrid.forEachCoordinate { coordinate ->
            if (origin.distanceSq(coordinate) > visDistanceSquared) {
                visibilityGrid[coordinate] = false
            }
        }
    }

    /**
     * returns true if any part of [row] is within [bounds]
     */
    private fun rowInBounds(
        row: Row,
        bounds: IntRect,
    ): Boolean {
        val gridPosition =
            when (row.quadrant.direction) {
                CardinalDirection.NORTH -> Vector2Int(row.quadrant.origin.x, row.quadrant.origin.y - row.depth)
                CardinalDirection.EAST -> Vector2Int(row.quadrant.origin.x + row.depth, row.quadrant.origin.y)
                CardinalDirection.SOUTH -> Vector2Int(row.quadrant.origin.x, row.quadrant.origin.y + row.depth)
                CardinalDirection.WEST -> Vector2Int(row.quadrant.origin.x - row.depth, row.quadrant.origin.y)
            }

        return bounds.contains(gridPosition)
    }

    private fun calculateSlope(quadrantPoint: QuadrantPoint): Fraction =
        Fraction(2 * quadrantPoint.column - 1, 2 * quadrantPoint.row)

    private fun isSymmetric(
        row: Row,
        quadrantPoint: QuadrantPoint,
    ): Boolean {
        return row.startSlope.multiply(row.depth) <= quadrantPoint.column &&
            row.endSlope.multiply(row.depth) >= quadrantPoint.column
    }
}

private operator fun Fraction.compareTo(otherValue: Int): Int {
    return this.toDouble().compareTo(otherValue)
}
