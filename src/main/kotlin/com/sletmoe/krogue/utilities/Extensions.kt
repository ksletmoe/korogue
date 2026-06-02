package com.sletmoe.krogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import java.util.concurrent.locks.ReentrantLock

operator fun Vector2Int.plus(other: Vector2Int): Vector2Int = Vector2Int(x + other.x, y + other.y)

operator fun Vector2Int.minus(other: Vector2Int): Vector2Int = Vector2Int(x - other.x, y - other.y)

fun IntRect.translated(
    dx: Int,
    dy: Int,
): IntRect = IntRect(x + dx, y + dy, width, height)

fun IntRect.scaled(
    widthScaleFactor: Int,
    heightScaleFactor: Int,
): IntRect {
    return IntRect(
        x * widthScaleFactor,
        y * heightScaleFactor,
        width * widthScaleFactor,
        height * heightScaleFactor,
    )
}

val IntRect.lastX: Int
    get() = x + width - 1

val IntRect.lastY: Int
    get() = y + height - 1

inline fun <T> Iterable<T>.nonOverflowingSumOf(selector: (T) -> Int): Int {
    var accumulator = 0
    this.forEach { element ->
        accumulator = nonOverflowingAdd(accumulator, selector(element))
    }
    return accumulator
}

inline fun <T> ReentrantLock.withLock(action: () -> T) {
    lock()
    try {
        action()
    } finally {
        unlock()
    }
}

infix fun Grid<Boolean>.or(other: Grid<Boolean>): Grid<Boolean> {
    val newGrid = Grid.of(this)

    other.forEachCoordinate { coordinate ->
        newGrid[coordinate] = newGrid[coordinate] || other[coordinate]
    }

    return newGrid
}
