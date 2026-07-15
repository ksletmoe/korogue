package com.sletmoe.korogue.algorithms.zonegen

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * A plain floor tile used as the groundTile in walker tests.
 * Color constants are static final fields on the libGDX Color class — no native
 * initialisation is needed to use them.
 */
private val FLOOR_TILE =
    Tile(
        name = "Floor",
        glyph = '.',
        color = Color.WHITE,
        backgroundColor = Color.BLACK,
        isWalkable = true,
        blocksLineOfSight = false,
    )

private fun blankGrid(
    width: Int,
    height: Int,
): Grid<Tile> = Grid(width, height, BLANK_TILE)

class RandomWalkCaveTest : DescribeSpec({

    describe("randomWalkCave") {
        it("should return a generator whose result is within grid bounds") {
            val grid = blankGrid(20, 20)
            val generator = randomWalkCave(10, 10, length = 50, groundTile = FLOOR_TILE)
            val result: Vector2Int = generator(grid, Random.Default)

            (result.x >= 0).shouldBeTrue()
            (result.y >= 0).shouldBeTrue()
            (result.x < grid.width).shouldBeTrue()
            (result.y < grid.height).shouldBeTrue()
        }

        it("should paint at least one cell with the groundTile when length is positive") {
            val grid = blankGrid(20, 20)
            val generator = randomWalkCave(10, 10, length = 10, groundTile = FLOOR_TILE)
            generator(grid, Random.Default)

            var groundCellCount = 0
            grid.forEachCoordinate { coord ->
                if (grid[coord] == FLOOR_TILE) groundCellCount++
            }
            groundCellCount shouldBeGreaterThanOrEqual 1
        }

        it("should paint no cells when length is 0") {
            // repeat(0) runs zero iterations — no tiles are set
            val grid = blankGrid(20, 20)
            val generator = randomWalkCave(10, 10, length = 0, groundTile = FLOOR_TILE)
            generator(grid, Random.Default)

            var anyPainted = false
            grid.forEachCoordinate { if (grid[it] == FLOOR_TILE) anyPainted = true }
            anyPainted.shouldBeFalse()
        }

        it("should never paint a cell outside the grid boundaries") {
            // If painting ever went out of bounds, Grid.set would throw RuntimeException.
            // Running without exception is the assertion.
            val grid = blankGrid(15, 15)
            val generator = randomWalkCave(7, 7, length = 300, groundTile = FLOOR_TILE)
            generator(grid, Random.Default)
        }

        it("should never paint the rightmost column (lastColumnIndex)") {
            // Guard condition: x + 1 < lastColumnIndex means x never reaches lastColumnIndex.
            val width = 12
            val height = 12
            val grid = blankGrid(width, height)
            val generator = randomWalkCave(6, 6, length = 500, groundTile = FLOOR_TILE)
            generator(grid, Random.Default)

            val lastCol = grid.lastColumnIndex
            var borderpainted = false
            (0 until grid.height).forEach { y ->
                if (grid[lastCol, y] == FLOOR_TILE) borderpainted = true
            }
            borderpainted.shouldBeFalse()
        }

        it("should never paint the bottom row (lastRowIndex)") {
            // Guard condition: y + 1 < lastRowIndex means y never reaches lastRowIndex.
            val width = 12
            val height = 12
            val grid = blankGrid(width, height)
            val generator = randomWalkCave(6, 6, length = 500, groundTile = FLOOR_TILE)
            generator(grid, Random.Default)

            val lastRow = grid.lastRowIndex
            var borderpainted = false
            (0 until grid.width).forEach { x ->
                if (grid[x, lastRow] == FLOOR_TILE) borderpainted = true
            }
            borderpainted.shouldBeFalse()
        }

        it("should produce identical tile patterns for the same fixed seed") {
            val seed = 42L
            val gridA = blankGrid(20, 20)
            val gridB = blankGrid(20, 20)

            val generator = randomWalkCave(10, 10, length = 100, groundTile = FLOOR_TILE)
            val resultA = generator(gridA, Random(seed))
            val resultB = generator(gridB, Random(seed))

            resultA shouldBe resultB

            var allMatch = true
            gridA.forEachCoordinate { coord ->
                if (gridA[coord] != gridB[coord]) allMatch = false
            }
            allMatch.shouldBeTrue()
        }

        it("should produce a different final position for a clearly different seed") {
            // Seed 1L vs seed 9999L with 200 steps on a 20x20 grid. Because the walker
            // is deterministic we can hard-code that these two seeds differ — verified
            // by inspection rather than a probabilistic assertion.
            val gridA = blankGrid(20, 20)
            val gridB = blankGrid(20, 20)

            val generator = randomWalkCave(10, 10, length = 200, groundTile = FLOOR_TILE)
            val resultA = generator(gridA, Random(1L))
            val resultB = generator(gridB, Random(9999L))

            // At least one of the two final positions must differ — they cannot both be
            // identical after 200 steps with completely different random streams.
            // We verify by checking either the coordinates or the painted cells differ.
            val endpointsDiffer = resultA != resultB
            var paintingsDiffer = false
            gridA.forEachCoordinate { coord ->
                if (gridA[coord] != gridB[coord]) paintingsDiffer = true
            }
            (endpointsDiffer || paintingsDiffer).shouldBeTrue()
        }

        it("should return the final walker position which has been painted with groundTile") {
            val grid = blankGrid(20, 20)
            val generator = randomWalkCave(10, 10, length = 50, groundTile = FLOOR_TILE)
            val finalPos = generator(grid, Random(77L))

            // The final position is always painted on the last iteration of repeat(length)
            (grid[finalPos] == FLOOR_TILE).shouldBeTrue()
        }
    }
})
