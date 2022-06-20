package com.sletmoe.krogue.utilities

import java.awt.Point

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

    private val rows: MutableList<MutableList<T>> = MutableList(width) {
        MutableList(height) {
            defaultValue
        }
    }

    fun getElement(x: Int, y: Int): T {
        checkBounds(x, y)
        return rows[x][y]
    }

    fun setElement(x: Int, y: Int, value: T) {
        checkBounds(x, y)
        rows[x][y] = value
    }

    operator fun get(coord: Point): T = getElement(coord.x, coord.y)
    operator fun set(coord: Point, value: T) = setElement(coord.x, coord.y, value)

    operator fun get(x: Int, y: Int): T = getElement(x, y)
    operator fun set(x: Int, y: Int, value: T) = setElement(x, y, value)

    private fun checkBounds(x: Int, y: Int) {
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

    companion object {
        fun <T> of(other: Grid<T>): Grid<T> {
            if (other.width < 1 || other.height < 1) {
                throw RuntimeException("Can't copy an empty Grid")
            }

            return Grid(other)
        }
    }
}
