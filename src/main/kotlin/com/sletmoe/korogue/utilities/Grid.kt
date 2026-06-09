package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class Grid<T>(width: Int, height: Int, defaultValue: T) {
    private constructor(other: Grid<T>) : this(other.width, other.height, other[0, 0]) {
        this.forEachCoordinate { coordinate ->
            this[coordinate] = other[coordinate]
        }
    }

    val width: Int
        get() = rows.size
    val height: Int
        get() = rows[0].size

    val lastColumnIndex: Int = width - 1
    val lastRowIndex: Int = height - 1

    private val rows: MutableList<MutableList<T>> =
        MutableList(width) {
            MutableList(height) {
                defaultValue
            }
        }

    private fun getElement(
        x: Int,
        y: Int,
    ): T {
        checkBounds(x, y)
        return rows[x][y]
    }

    private fun setElement(
        x: Int,
        y: Int,
        value: T,
    ) {
        checkBounds(x, y)
        rows[x][y] = value
    }

    operator fun get(coord: Vector2Int): T = getElement(coord.x, coord.y)

    operator fun set(
        coord: Vector2Int,
        value: T,
    ) = setElement(coord.x, coord.y, value)

    operator fun get(
        x: Int,
        y: Int,
    ): T = getElement(x, y)

    operator fun set(
        x: Int,
        y: Int,
        value: T,
    ) = setElement(x, y, value)

    fun fill(value: T) {
        forEachCoordinate { set(it, value) }
    }

    private fun checkBounds(
        x: Int,
        y: Int,
    ) {
        if (x < 0 || x > rows.lastIndex) {
            throw RuntimeException("($x, $y) is not within Grid column bounds: 0 - ${rows.lastIndex}")
        } else if (y < 0 || y > rows[x].lastIndex) {
            throw RuntimeException("($x, $y) is not within Grid row bounds: 0 - ${rows[x].lastIndex}")
        }
    }

    fun forEach(action: (T) -> Unit) {
        forEachCoordinate { action(get(it)) }
    }

    fun forEachIndexed(action: (Vector2Int, T) -> Unit) {
        forEachCoordinate { action(it, get(it)) }
    }

    fun forEachCoordinate(action: (Vector2Int) -> Unit) {
        (0..lastColumnIndex).forEach { x ->
            (0..lastRowIndex).forEach { y ->
                action(Vector2Int(x, y))
            }
        }
    }

    fun forEachCoordinateInRadius(
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

    private fun boundingBoxForCircle(
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

    companion object {
        fun <T> of(other: Grid<T>): Grid<T> {
            if (other.width < 1 || other.height < 1) {
                throw RuntimeException("Can't copy an empty Grid")
            }

            return Grid(other)
        }
    }
}
