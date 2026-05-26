package com.sletmoe.kotile.utilities

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GridTest : FunSpec({
    test("a new grid is filled with the default value") {
        val grid = Grid(3, 2, 0)
        grid.forEachIndexed { _, _, value -> value shouldBe 0 }
    }

    test("set then get returns the stored value") {
        val grid = Grid(3, 3, -1)
        grid[1, 2] = 42
        grid[1, 2] shouldBe 42
        grid[0, 0] shouldBe -1
    }

    test("cells are addressed independently") {
        val grid = Grid(2, 2, 0)
        grid[0, 0] = 1
        grid[1, 0] = 2
        grid[0, 1] = 3
        grid[1, 1] = 4
        listOf(grid[0, 0], grid[1, 0], grid[0, 1], grid[1, 1]) shouldBe listOf(1, 2, 3, 4)
    }

    test("fill overwrites every cell") {
        val grid = Grid(2, 2, 0)
        grid[0, 0] = 9
        grid.fill(7)
        grid.forEachIndexed { _, _, value -> value shouldBe 7 }
    }

    test("clear resets every cell to the default value") {
        val grid = Grid(2, 2, 5)
        grid[1, 1] = 0
        grid.clear()
        grid[1, 1] shouldBe 5
    }

    test("forEachIndexed visits every cell with its coordinates") {
        val grid = Grid(2, 2, 1)
        grid[1, 0] = 8

        val visited = mutableMapOf<Pair<Int, Int>, Int>()
        grid.forEachIndexed { x, y, value -> visited[x to y] = value }

        visited shouldBe mapOf(
            (0 to 0) to 1,
            (0 to 1) to 1,
            (1 to 0) to 8,
            (1 to 1) to 1,
        )
    }

    test("out of bounds access throws") {
        val grid = Grid(2, 2, 0)
        shouldThrow<IndexOutOfBoundsException> { grid[2, 0] }
        shouldThrow<IndexOutOfBoundsException> { grid[0, 2] }
        shouldThrow<IndexOutOfBoundsException> { grid[-1, 0] }
        shouldThrow<IndexOutOfBoundsException> { grid[0, -1] = 1 }
    }
})
