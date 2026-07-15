package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class GridExtensionsTest : DescribeSpec({

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

            // center, north, west, east, south — the diagonals sit at sqrt(2) > 1.
            val expected =
                setOf(
                    Vector2Int(2, 2),
                    Vector2Int(2, 1),
                    Vector2Int(1, 2),
                    Vector2Int(3, 2),
                    Vector2Int(2, 3),
                )
            visited shouldBe expected
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

        it("should visit every cell of the grid when the radius covers it entirely") {
            val grid = Grid(4, 4, 0)
            val visited = mutableSetOf<Vector2Int>()
            grid.forEachCoordinateInRadius(Vector2Int(1, 1), 100.0) { visited.add(it) }
            visited.size shouldBe 16
        }
    }

    describe("Grid<Boolean>.or") {
        it("should set a cell true when it is true in either operand") {
            val a = Grid(3, 3, false)
            val b = Grid(3, 3, false)
            a[0, 0] = true
            b[2, 2] = true

            val result = a or b

            result[0, 0].shouldBeTrue()
            result[2, 2].shouldBeTrue()
        }

        it("should leave a cell false when it is false in both operands") {
            val a = Grid(3, 3, false)
            val b = Grid(3, 3, false)
            a[0, 0] = true

            (a or b)[1, 1].shouldBeFalse()
        }

        it("should not mutate either operand") {
            val a = Grid(2, 2, false)
            val b = Grid(2, 2, false)
            b[1, 1] = true

            a or b

            a[1, 1].shouldBeFalse()
            b[0, 0].shouldBeFalse()
        }

        it("should be commutative") {
            val a = Grid(3, 3, false)
            val b = Grid(3, 3, false)
            a[0, 1] = true
            b[2, 0] = true

            val ab = a or b
            val ba = b or a

            ab.forEachCoordinate { ab[it] shouldBe ba[it] }
        }
    }
})
