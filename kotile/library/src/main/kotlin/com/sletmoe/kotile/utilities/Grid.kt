package com.sletmoe.kotile.utilities

/**
 * A fixed-size, dense 2D array addressed by `[x, y]` or by [Vector2Int], backed
 * by a single row-major [Array].
 *
 * The backing store uses `Array<Any?>` to avoid the erasure restriction on
 * generic [Array] construction. All external reads and writes are cast through
 * the unchecked-cast suppression below; the class invariant (every slot holds a
 * `T`) is maintained by the constructor, [set], and [fill].
 *
 * @param T element type
 * @param defaultValue value every cell starts at and is reset to by [clear]
 * @property width number of columns
 * @property height number of rows
 */
class Grid<T>(val width: Int, val height: Int, private val defaultValue: T) {
    @Suppress("UNCHECKED_CAST")
    private val values: Array<Any?> = Array(width * height) { defaultValue }

    /** Largest valid `x`, or `-1` for a zero-width grid. */
    val lastColumnIndex: Int get() = width - 1

    /** Largest valid `y`, or `-1` for a zero-height grid. */
    val lastRowIndex: Int get() = height - 1

    /**
     * Returns the value at column [x], row [y].
     *
     * @throws IndexOutOfBoundsException if the coordinates are outside the grid
     */
    @Suppress("UNCHECKED_CAST")
    operator fun get(
        x: Int,
        y: Int,
    ): T {
        checkIndices(x, y)
        return values[y * width + x] as T
    }

    /**
     * Stores [value] at column [x], row [y].
     *
     * @throws IndexOutOfBoundsException if the coordinates are outside the grid
     */
    operator fun set(
        x: Int,
        y: Int,
        value: T,
    ) {
        checkIndices(x, y)
        values[y * width + x] = value
    }

    /**
     * Returns the value at [coordinate].
     *
     * @throws IndexOutOfBoundsException if the coordinate is outside the grid
     */
    operator fun get(coordinate: Vector2Int): T = get(coordinate.x, coordinate.y)

    /**
     * Stores [value] at [coordinate].
     *
     * @throws IndexOutOfBoundsException if the coordinate is outside the grid
     */
    operator fun set(
        coordinate: Vector2Int,
        value: T,
    ) = set(coordinate.x, coordinate.y, value)

    /** Sets every cell to [value]. */
    fun fill(value: T) {
        values.fill(value)
    }

    /** Resets every cell to the default value the grid was created with. */
    fun clear() {
        fill(defaultValue)
    }

    /** Invokes [action] with the value of every cell, in [forEachCoordinate] order. */
    fun forEach(action: (T) -> Unit) {
        forEachCoordinate { action(get(it)) }
    }

    /** Invokes [action] with the column, row, and value of every cell. */
    fun forEachIndexed(action: (Int, Int, T) -> Unit) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                action(x, y, get(x, y))
            }
        }
    }

    /**
     * Invokes [action] with the coordinate of every cell, column-major: all rows of
     * column 0, then all rows of column 1, and so on. The order is stable, so callers
     * that accumulate into an ordered structure stay deterministic.
     */
    fun forEachCoordinate(action: (Vector2Int) -> Unit) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                action(Vector2Int(x, y))
            }
        }
    }

    /**
     * Returns an independent grid with the same dimensions, default value, and cell
     * values as this one. Mutating either grid afterwards does not affect the other.
     */
    fun copy(): Grid<T> {
        val copy = Grid(width, height, defaultValue)
        values.copyInto(copy.values)
        return copy
    }

    private fun checkIndices(
        x: Int,
        y: Int,
    ) {
        if (x < 0 || x >= width) {
            throw IndexOutOfBoundsException("x = $x")
        }

        if (y < 0 || y >= height) {
            throw IndexOutOfBoundsException("y = $y")
        }
    }
}
