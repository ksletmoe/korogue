package com.sletmoe.krogue.utilities

import java.awt.Point
import java.awt.Rectangle
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

    operator fun get(coord: Point): T = getElement(coord.x, coord.y)

    operator fun set(
        coord: Point,
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

    fun forEachIndexed(action: (Point, T) -> Unit) {
        forEachCoordinate { action(it, get(it)) }
    }

    fun forEachCoordinate(action: (Point) -> Unit) {
        (0..lastColumnIndex).forEach { x ->
            (0..lastRowIndex).forEach { y ->
                action(Point(x, y))
            }
        }
    }

    fun forEachCoordinateInRadius(
        center: Point,
        radius: Double,
        action: (Point) -> Unit,
    ) {
        val boundingBox = boundingBoxForCircle(center, radius)
        val radiusSquared = radius * radius

        (boundingBox.x until boundingBox.x + boundingBox.width).forEach { x ->
            (boundingBox.y until boundingBox.y + boundingBox.height).forEach { y ->
                val coordinate = Point(x, y)

                if (center.distanceSq(coordinate) <= radiusSquared) {
                    action(coordinate)
                }
            }
        }
    }

    private fun boundingBoxForCircle(
        center: Point,
        radius: Double,
    ): Rectangle {
        val radiusInt = ceil(radius).toInt()

        val x = max(0, center.x - radiusInt)
        val y = max(0, center.y - radiusInt)
        val width = min(lastColumnIndex, center.x + radiusInt) - x
        val height = min(lastRowIndex, center.y + radiusInt) - y

        return Rectangle(x, y, width, height)
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
