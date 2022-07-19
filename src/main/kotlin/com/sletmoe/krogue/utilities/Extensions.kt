package com.sletmoe.krogue.utilities

import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.locks.ReentrantLock

operator fun Point.plus(other: Point): Point = Point(x + other.x, y + other.y)
operator fun Point.minus(other: Point): Point = Point(x - other.x, y - other.y)

fun Rectangle.translated(dx: Int, dy: Int): Rectangle = Rectangle(this).also { it.translate(dx, dy) }
fun Rectangle.scaled(widthScaleFactor: Int, heightScaleFactor: Int): Rectangle {
    return Rectangle(
        x * widthScaleFactor,
        y * heightScaleFactor,
        width * widthScaleFactor,
        height * heightScaleFactor,
    )
}

val Rectangle.lastX: Int
    get() = x + width - 1

val Rectangle.lastY: Int
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
