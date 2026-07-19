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

        visited shouldBe
            mapOf(
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

    test("width and height report the dimensions the grid was built with") {
        val grid = Grid(5, 3, 0)
        grid.width shouldBe 5
        grid.height shouldBe 3
    }

    test("lastColumnIndex and lastRowIndex are the inclusive upper bounds") {
        val grid = Grid(5, 3, 0)
        grid.lastColumnIndex shouldBe 4
        grid.lastRowIndex shouldBe 2
        grid[grid.lastColumnIndex, grid.lastRowIndex] shouldBe 0
    }

    test("Vector2Int access round-trips and agrees with the x, y overload") {
        val grid = Grid(5, 5, 0)
        val coord = Vector2Int(3, 2)
        grid[coord] = 7
        grid[coord] shouldBe 7
        grid[3, 2] shouldBe 7

        grid[1, 4] = 9
        grid[Vector2Int(1, 4)] shouldBe 9
    }

    test("Vector2Int access does not transpose x and y") {
        // Guard against the row-major index arithmetic silently swapping axes on a
        // non-square grid, which a width == height grid would not catch.
        val grid = Grid(4, 2, 0)
        grid[Vector2Int(3, 1)] = 5
        grid[3, 1] shouldBe 5
        shouldThrow<IndexOutOfBoundsException> { grid[Vector2Int(1, 3)] }
    }

    test("out of bounds Vector2Int access throws") {
        val grid = Grid(2, 2, 0)
        shouldThrow<IndexOutOfBoundsException> { grid[Vector2Int(2, 0)] }
        shouldThrow<IndexOutOfBoundsException> { grid[Vector2Int(0, -1)] = 1 }
    }

    test("forEach visits every cell's value") {
        val grid = Grid(3, 2, 1)
        grid[0, 0] = 4

        val seen = mutableListOf<Int>()
        grid.forEach { seen.add(it) }

        seen.size shouldBe 6
        seen.sum() shouldBe 9 // five 1s plus the 4
    }

    test("forEachCoordinate visits every distinct coordinate exactly once") {
        val grid = Grid(3, 4, 0)
        val visited = mutableListOf<Vector2Int>()
        grid.forEachCoordinate { visited.add(it) }

        visited.size shouldBe 12
        visited.toSet().size shouldBe 12
        visited.contains(Vector2Int(0, 0)) shouldBe true
        visited.contains(Vector2Int(2, 3)) shouldBe true
    }

    test("forEachCoordinate visits in a stable column-major order") {
        // Callers accumulating into ordered structures rely on this being deterministic.
        val grid = Grid(2, 3, 0)
        val visited = mutableListOf<Vector2Int>()
        grid.forEachCoordinate { visited.add(it) }

        visited shouldBe
            listOf(
                Vector2Int(0, 0), Vector2Int(0, 1), Vector2Int(0, 2),
                Vector2Int(1, 0), Vector2Int(1, 1), Vector2Int(1, 2),
            )
    }

    test("copy carries over every cell value") {
        val source = Grid(3, 3, 1)
        source[1, 1] = 99
        val copy = source.copy()

        copy.width shouldBe 3
        copy.height shouldBe 3
        copy.forEachIndexed { x, y, value -> value shouldBe source[x, y] }
        copy[1, 1] shouldBe 99
    }

    test("copy is independent of the source in both directions") {
        val source = Grid(3, 3, 0)
        val copy = source.copy()

        copy[0, 0] = 55
        source[0, 0] shouldBe 0

        source[2, 2] = 7
        copy[2, 2] shouldBe 0
    }

    test("copy preserves the default value so clear resets correctly") {
        val source = Grid(2, 2, 5)
        source[0, 0] = 1
        val copy = source.copy()

        copy.clear()

        copy.forEachIndexed { _, _, value -> value shouldBe 5 }
    }

    test("an empty grid is usable and copyable") {
        // The list-of-lists Grid this replaced could not copy a zero-sized grid.
        val grid = Grid(0, 0, 0)
        grid.lastColumnIndex shouldBe -1

        var visited = 0
        grid.copy().forEachCoordinate { visited++ }
        visited shouldBe 0
    }
})
