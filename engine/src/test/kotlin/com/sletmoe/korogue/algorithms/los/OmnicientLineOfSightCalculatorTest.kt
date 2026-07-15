package com.sletmoe.korogue.algorithms.los

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * A wall tile that blocks LOS — irrelevant to Omnicient LOS, whose whole contract is ignoring walls.
 */
private val WALL_TILE =
    Tile(
        name = "Wall",
        glyph = '#',
        color = Color.GRAY,
        backgroundColor = Color.DARK_GRAY,
        isWalkable = false,
        blocksLineOfSight = true,
    )

private val FLOOR_TILE = BLANK_TILE

class OmnicientLineOfSightCalculatorTest : DescribeSpec({

    val calculator = OmnicientLineOfSightCalculator()

    describe("all-floor grid") {
        it("should mark every in-bounds cell visible") {
            val grid = Grid(9, 9, FLOOR_TILE)
            val origin = Vector2Int(4, 4)
            val visible = calculator.calculateLineOfSight(origin, grid, null)

            var allVisible = true
            visible.forEachCoordinate { coord ->
                if (!visible[coord]) allVisible = false
            }
            allVisible.shouldBeTrue()
        }
    }

    describe("walls do not block") {
        it("should mark cells visible even when walls surround the origin") {
            val grid = Grid<Tile>(5, 5, WALL_TILE)
            val origin = Vector2Int(2, 2)
            grid[origin] = FLOOR_TILE

            val visible = calculator.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(0, 0)].shouldBeTrue()
            visible[Vector2Int(4, 4)].shouldBeTrue()
            visible[Vector2Int(0, 4)].shouldBeTrue()
            visible[Vector2Int(4, 0)].shouldBeTrue()
        }

        it("should mark a cell visible even directly behind an unbroken wall from the origin") {
            // Layout (y increases downward):
            //   . . . . .   y=0   ← target (2,0), directly behind the wall row
            //   W W W W W   y=1   ← full wall row
            //   . . O . .   y=2   ← origin (2,2)
            val grid = Grid(5, 3, FLOOR_TILE)
            (0 until 5).forEach { x -> grid[x, 1] = WALL_TILE }
            val origin = Vector2Int(2, 2)

            val visible = calculator.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(2, 0)].shouldBeTrue()
        }
    }

    describe("maxViewDistance is ignored") {
        it("should still mark far cells visible when maxViewDistance is very small") {
            val grid = Grid(21, 21, FLOOR_TILE)
            val origin = Vector2Int(10, 10)

            val visible = calculator.calculateLineOfSight(origin, grid, 0.0)

            visible[Vector2Int(0, 0)].shouldBeTrue()
            visible[Vector2Int(20, 20)].shouldBeTrue()
        }
    }

    describe("symmetry") {
        it("should be symmetric between any two in-bounds cells") {
            val grid = Grid(6, 6, FLOOR_TILE)
            val a = Vector2Int(0, 0)
            val b = Vector2Int(5, 5)

            val fromA = calculator.calculateLineOfSight(a, grid, null)
            val fromB = calculator.calculateLineOfSight(b, grid, null)

            fromA[b].shouldBeTrue()
            fromB[a].shouldBeTrue()
        }
    }

    describe("output grid dimensions") {
        it("should return a visibility grid matching the tile grid's width and height") {
            val grid = Grid(7, 9, FLOOR_TILE)
            val origin = Vector2Int(3, 4)
            val visible = calculator.calculateLineOfSight(origin, grid, null)

            visible.width shouldBe grid.width
            visible.height shouldBe grid.height
        }
    }
})
