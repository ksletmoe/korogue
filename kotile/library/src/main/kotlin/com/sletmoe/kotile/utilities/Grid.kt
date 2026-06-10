package com.sletmoe.kotile.utilities

/**
 * A fixed-size, dense 2D array addressed by `[x, y]`, backed by a single
 * row-major [Array].
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

    /**
     * Returns the value at column [x], row [y].
     *
     * @throws IndexOutOfBoundsException if the coordinates are outside the grid
     */
    @Suppress("UNCHECKED_CAST")
    operator fun get(x: Int, y: Int): T {
        checkIndices(x, y)
        return values[y * width + x] as T
    }

    /**
     * Stores [value] at column [x], row [y].
     *
     * @throws IndexOutOfBoundsException if the coordinates are outside the grid
     */
    operator fun set(x: Int, y: Int, value: T) {
        checkIndices(x, y)
        values[y * width + x] = value
    }

    /** Sets every cell to [value]. */
    fun fill(value: T) {
        values.fill(value)
    }

    /** Resets every cell to the default value the grid was created with. */
    fun clear() {
        fill(defaultValue)
    }

    /** Invokes [action] with the column, row, and value of every cell. */
    fun forEachIndexed(action: (Int, Int, T) -> Unit) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                action(x, y, get(x, y))
            }
        }
    }

    private fun checkIndices(x: Int, y: Int) {
        if (x < 0 || x >= width) {
            throw IndexOutOfBoundsException("x = $x")
        }

        if (y < 0 || y >= height) {
            throw IndexOutOfBoundsException("y = $y")
        }
    }
}
