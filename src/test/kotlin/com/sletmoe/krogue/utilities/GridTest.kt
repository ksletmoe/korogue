package com.sletmoe.krogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class GridTest : DescribeSpec({

    describe("Grid construction") {
        it("should have the specified width and height") {
            val grid = Grid(5, 3, 0)
            grid.width shouldBe 5
            grid.height shouldBe 3
        }

        it("should initialise every cell to the default value") {
            val grid = Grid(4, 4, 42)
            var allMatch = true
            grid.forEachCoordinate { coord ->
                if (grid[coord] != 42) allMatch = false
            }
            allMatch.shouldBeTrue()
        }
    }

    describe("get and set via (x, y) overload") {
        it("should return the value previously set at the same coordinates") {
            val grid = Grid(5, 5, 0)
            grid[2, 3] = 99
            grid[2, 3] shouldBe 99
        }

        it("should not affect adjacent cells when setting a value") {
            val grid = Grid(5, 5, 0)
            grid[2, 3] = 99
            grid[2, 4] shouldBe 0
        }
    }

    describe("get and set via Vector2Int overload") {
        it("should round-trip a value stored via Vector2Int") {
            val grid = Grid(5, 5, false)
            val coord = Vector2Int(1, 4)
            grid[coord] = true
            grid[coord].shouldBeTrue()
        }

        it("should produce the same result as the (x, y) overload for the same coordinate") {
            val grid = Grid(5, 5, 0)
            val coord = Vector2Int(3, 2)
            grid[coord] = 7
            grid[3, 2] shouldBe 7
        }
    }

    describe("out-of-bounds access") {
        it("should throw RuntimeException when x is negative") {
            val grid = Grid(5, 5, 0)
            shouldThrow<RuntimeException> { grid[-1, 0] }
        }

        it("should throw RuntimeException when y is negative") {
            val grid = Grid(5, 5, 0)
            shouldThrow<RuntimeException> { grid[0, -1] }
        }

        it("should throw RuntimeException when x equals width") {
            val grid = Grid(5, 5, 0)
            shouldThrow<RuntimeException> { grid[5, 0] }
        }

        it("should throw RuntimeException when y equals height") {
            val grid = Grid(5, 5, 0)
            shouldThrow<RuntimeException> { grid[0, 5] }
        }
    }

    describe("forEachCoordinate") {
        it("should visit exactly width * height cells") {
            val grid = Grid(4, 6, 0)
            var count = 0
            grid.forEachCoordinate { count++ }
            count shouldBe 24
        }

        it("should visit every distinct (x, y) pair exactly once") {
            val width = 3
            val height = 3
            val grid = Grid(width, height, 0)
            val visited = mutableSetOf<Pair<Int, Int>>()
            grid.forEachCoordinate { coord ->
                val pair = Pair(coord.x, coord.y)
                visited.add(pair) shouldBe true   // set.add returns false on duplicates
            }
            visited.size shouldBe width * height
        }

        it("should include the corner coordinates (0,0) and (lastColumnIndex, lastRowIndex)") {
            val grid = Grid(3, 4, 0)
            val visited = mutableSetOf<Vector2Int>()
            grid.forEachCoordinate { visited.add(it) }
            visited.contains(Vector2Int(0, 0)).shouldBeTrue()
            visited.contains(Vector2Int(2, 3)).shouldBeTrue()
        }
    }

    describe("forEachCoordinateInRadius") {
        it("should include the center coordinate itself") {
            val grid = Grid(10, 10, 0)
            val center = Vector2Int(5, 5)
            val visited = mutableSetOf<Vector2Int>()
            grid.forEachCoordinateInRadius(center, 3.0) { visited.add(it) }
            visited.contains(center).shouldBeTrue()
        }

        it("should not yield any coordinate whose distance from center exceeds the radius") {
            val grid = Grid(10, 10, 0)
            val center = Vector2Int(5, 5)
            val radius = 2.0
            val radiusSq = radius * radius
            var violation = false
            grid.forEachCoordinateInRadius(center, radius) { coord ->
                val dx = (coord.x - center.x).toDouble()
                val dy = (coord.y - center.y).toDouble()
                if (dx * dx + dy * dy > radiusSq) violation = true
            }
            violation.shouldBeFalse()
        }

        it("should yield the center and all four orthogonal neighbours for a radius-1 circle") {
            // Regression guard for the boundingBoxForCircle off-by-one: the east/south
            // boundary tiles at exactly center + radius must be visited, not just
            // north/west. (The diagonals are at distance sqrt(2) > 1 and excluded.)
            val grid = Grid(5, 5, 0)
            val center = Vector2Int(2, 2)
            val visited = mutableSetOf<Vector2Int>()
            grid.forEachCoordinateInRadius(center, 1.0) { visited.add(it) }

            visited.contains(Vector2Int(2, 2)).shouldBeTrue()  // center
            visited.contains(Vector2Int(2, 1)).shouldBeTrue()  // north (y - 1)
            visited.contains(Vector2Int(1, 2)).shouldBeTrue()  // west  (x - 1)
            visited.contains(Vector2Int(3, 2)).shouldBeTrue()  // east  (x + 1)
            visited.contains(Vector2Int(2, 3)).shouldBeTrue()  // south (y + 1)
        }

        it("should visit every coordinate inside a generous radius without false positives") {
            // Use a large radius (3.0) on a centred origin so the bounding-box does not
            // clip any in-radius cell.  Every yielded cell must be within the radius.
            val grid = Grid(11, 11, 0)
            val center = Vector2Int(5, 5)
            val radius = 3.0
            val radiusSq = radius * radius
            var violation = false
            grid.forEachCoordinateInRadius(center, radius) { coord ->
                val dx = (coord.x - center.x).toDouble()
                val dy = (coord.y - center.y).toDouble()
                if (dx * dx + dy * dy > radiusSq) violation = true
            }
            violation.shouldBeFalse()
        }

        it("should clamp visited coordinates to the grid bounds when center is near an edge") {
            val grid = Grid(5, 5, 0)
            val center = Vector2Int(0, 0)
            var violation = false
            grid.forEachCoordinateInRadius(center, 3.0) { coord ->
                if (coord.x < 0 || coord.y < 0 || coord.x >= 5 || coord.y >= 5) violation = true
            }
            violation.shouldBeFalse()
        }
    }

    describe("fill") {
        it("should set every cell to the supplied value") {
            val grid = Grid(3, 3, 0)
            grid.fill(7)
            var allSeven = true
            grid.forEachCoordinate { if (grid[it] != 7) allSeven = false }
            allSeven.shouldBeTrue()
        }
    }

    describe("Grid.of (copy constructor)") {
        it("should produce a grid with identical values to the source") {
            val source = Grid(3, 3, 1)
            source[1, 1] = 99
            val copy = Grid.of(source)
            copy[1, 1] shouldBe 99
        }

        it("should be an independent copy so mutations do not affect the original") {
            val source = Grid(3, 3, 0)
            val copy = Grid.of(source)
            copy[0, 0] = 55
            source[0, 0] shouldBe 0
        }
    }
})
