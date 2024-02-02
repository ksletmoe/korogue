package com.sletmoe.kotile.utilities

class Grid<T>(val width: Int, val height: Int, private val defaultValue: T) {
    private val values = mutableListOf<T>()

    init {
        repeat(width * height) {
            values.add(defaultValue)
        }
    }

    operator fun get(x: Int, y: Int): T {
        checkIndices(x, y)
        return values[y * width + x]
    }

    operator fun set(x: Int, y: Int, value: T) {
        checkIndices(x, y)
        values[y * width + x] = value
    }

    fun fill(value: T) {
        values.fill(value)
    }

    fun clear() {
        fill(defaultValue)
    }

    suspend fun forEachIndexed(action: suspend (Int, Int, T) -> Unit) {
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
