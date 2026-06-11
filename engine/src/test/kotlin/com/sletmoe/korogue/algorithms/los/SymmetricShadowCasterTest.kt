package com.sletmoe.korogue.algorithms.los

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * A wall tile that blocks LOS — the only property that matters for shadow casting.
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

/** Floor tile — see-through, walkable. BLANK_TILE already has blocksLineOfSight = false. */
private val FLOOR_TILE = BLANK_TILE

/**
 * Build a [size] x [size] grid filled with floor tiles, then apply [setup] to
 * place walls.
 */
private fun floorGrid(
    size: Int,
    setup: Grid<Tile>.(Grid<Tile>) -> Unit = {},
): Grid<Tile> {
    val grid = Grid(size, size, FLOOR_TILE)
    grid.setup(grid)
    return grid
}

/**
 * Build a rectangular [width] x [height] grid filled with floor tiles.
 */
private fun floorGrid(
    width: Int,
    height: Int,
): Grid<Tile> = Grid(width, height, FLOOR_TILE)

class SymmetricShadowCasterTest : DescribeSpec({

    val caster = SymmetricShadowCaster()

    // -------------------------------------------------------------------------
    // Origin visibility
    // -------------------------------------------------------------------------
    describe("origin tile visibility") {
        it("should mark the origin tile itself as visible") {
            val grid = floorGrid(10)
            val origin = Vector2Int(5, 5)
            val visible = caster.calculateLineOfSight(origin, grid, null)
            visible[origin].shouldBeTrue()
        }

        it("should mark the origin visible even when surrounded by walls") {
            val grid = Grid<Tile>(5, 5, WALL_TILE)
            val origin = Vector2Int(2, 2)
            // Place a floor at origin only so it is reachable
            grid[origin] = FLOOR_TILE
            val visible = caster.calculateLineOfSight(origin, grid, null)
            visible[origin].shouldBeTrue()
        }
    }

    // -------------------------------------------------------------------------
    // All-floor room: nearby tiles are visible
    // -------------------------------------------------------------------------
    describe("all-floor room") {
        it("should mark directly adjacent tiles as visible") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val visible = caster.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(5, 4)].shouldBeTrue() // north
            visible[Vector2Int(6, 5)].shouldBeTrue() // east
            visible[Vector2Int(5, 6)].shouldBeTrue() // south
            visible[Vector2Int(4, 5)].shouldBeTrue() // west
        }

        it("should mark diagonal neighbours as visible") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val visible = caster.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(6, 4)].shouldBeTrue() // northeast
            visible[Vector2Int(6, 6)].shouldBeTrue() // southeast
            visible[Vector2Int(4, 6)].shouldBeTrue() // southwest
            visible[Vector2Int(4, 4)].shouldBeTrue() // northwest
        }
    }

    // -------------------------------------------------------------------------
    // Shadow casting: wall blocks tiles behind it
    // -------------------------------------------------------------------------
    describe("wall shadow casting") {
        it("should block a tile that is directly behind a wall in the north direction") {
            // Layout (y increases downward):
            //   . . . . .   y=0
            //   . . . . .   y=1
            //   . . . . .   y=2   ← target (5,2) — behind the wall
            //   . . W . .   y=3   ← wall at (5,3)
            //   . . . . .   y=4
            //   . . O . .   y=5   ← origin (5,5)
            //   . . . . .   y=6
            // Going NORTH from origin, wall at (5,3) should shadow (5,2) and beyond.
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            grid[5, 3] = WALL_TILE

            val visible = caster.calculateLineOfSight(origin, grid, null)

            // Wall itself is visible (it's what blocks LOS)
            visible[Vector2Int(5, 3)].shouldBeTrue()
            // Tile directly behind the wall is NOT visible
            visible[Vector2Int(5, 2)].shouldBeFalse()
        }

        it("should block a tile directly behind a wall in the east direction") {
            // Origin at (5,5); wall at (7,5); target at (8,5).
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            grid[7, 5] = WALL_TILE

            val visible = caster.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(7, 5)].shouldBeTrue() // wall is visible
            visible[Vector2Int(8, 5)].shouldBeFalse() // behind wall, not visible
        }

        it("should still see tiles that are not behind the wall") {
            // Wall at (5,3) should not block tiles to the east or west of origin.
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            grid[5, 3] = WALL_TILE

            val visible = caster.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(6, 5)].shouldBeTrue() // east — not shadowed
            visible[Vector2Int(4, 5)].shouldBeTrue() // west — not shadowed
        }

        it("should block multiple tiles in a row behind a solid wall") {
            // Wall at (5,3); both (5,2) and (5,1) should be hidden from (5,5).
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            grid[5, 3] = WALL_TILE

            val visible = caster.calculateLineOfSight(origin, grid, null)

            visible[Vector2Int(5, 2)].shouldBeFalse()
            visible[Vector2Int(5, 1)].shouldBeFalse()
        }
    }

    // -------------------------------------------------------------------------
    // Symmetry: if A can see B, B can see A (all-floor grid)
    // -------------------------------------------------------------------------
    describe("LOS symmetry on all-floor grid") {
        it("should be symmetric: if origin can see a tile then that tile can see origin (direct north)") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val target = Vector2Int(5, 2) // 3 steps north

            val fromOrigin = caster.calculateLineOfSight(origin, grid, null)
            val fromTarget = caster.calculateLineOfSight(target, grid, null)

            fromOrigin[target].shouldBeTrue()
            fromTarget[origin].shouldBeTrue()
        }

        it("should be symmetric: if origin can see a diagonal tile then that tile can see origin") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val target = Vector2Int(8, 2) // 3 east, 3 north

            val fromOrigin = caster.calculateLineOfSight(origin, grid, null)
            val fromTarget = caster.calculateLineOfSight(target, grid, null)

            fromOrigin[target].shouldBeTrue()
            fromTarget[origin].shouldBeTrue()
        }

        it("should be symmetric for all visible tile pairs in an all-floor grid") {
            val size = 9
            val grid = floorGrid(size)
            val origin = Vector2Int(4, 4) // centre of 9x9 grid

            val fromOrigin = caster.calculateLineOfSight(origin, grid, null)

            // For every tile the origin can see, check the reverse LOS includes origin
            var asymmetryFound = false
            grid.forEachCoordinate { coord ->
                if (fromOrigin[coord]) {
                    val fromTile = caster.calculateLineOfSight(coord, grid, null)
                    if (!fromTile[origin]) asymmetryFound = true
                }
            }
            asymmetryFound.shouldBeFalse()
        }
    }

    // -------------------------------------------------------------------------
    // maxViewDistance limiting
    // -------------------------------------------------------------------------
    describe("maxViewDistance") {
        it("should not mark tiles beyond maxViewDistance as visible") {
            val grid = floorGrid(21)
            val origin = Vector2Int(10, 10)
            val maxDist = 3.0

            val visible = caster.calculateLineOfSight(origin, grid, maxDist)

            // A tile 5 steps north is beyond the radius of 3
            visible[Vector2Int(10, 5)].shouldBeFalse()
        }

        it("should still mark tiles within maxViewDistance as visible") {
            val grid = floorGrid(21)
            val origin = Vector2Int(10, 10)
            val maxDist = 5.0

            val visible = caster.calculateLineOfSight(origin, grid, maxDist)

            // A tile 3 steps north is well within radius 5
            visible[Vector2Int(10, 7)].shouldBeTrue()
        }

        it("should mark the origin as visible even when maxViewDistance is 0.0") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val visible = caster.calculateLineOfSight(origin, grid, 0.0)
            visible[origin].shouldBeTrue()
        }

        it("should not mark directly adjacent tiles as visible when maxViewDistance is less than 1") {
            val grid = floorGrid(11)
            val origin = Vector2Int(5, 5)
            val visible = caster.calculateLineOfSight(origin, grid, 0.5)

            // Adjacent tiles are distance 1.0 away — beyond the 0.5 limit
            visible[Vector2Int(5, 4)].shouldBeFalse()
        }

        it("should confine all visible tiles to within the maxViewDistance radius") {
            val grid = floorGrid(21)
            val origin = Vector2Int(10, 10)
            val maxDist = 4.0
            val maxDistSq = maxDist * maxDist

            val visible = caster.calculateLineOfSight(origin, grid, maxDist)

            var violation = false
            grid.forEachCoordinate { coord ->
                if (visible[coord]) {
                    val dx = (coord.x - origin.x).toDouble()
                    val dy = (coord.y - origin.y).toDouble()
                    if (dx * dx + dy * dy > maxDistSq) violation = true
                }
            }
            violation.shouldBeFalse()
        }
    }

    // -------------------------------------------------------------------------
    // Grid dimensions: output grid matches input grid dimensions
    // -------------------------------------------------------------------------
    describe("output grid dimensions") {
        it("should return a visibility grid with the same width as the tile grid") {
            val grid = floorGrid(7, 9)
            val origin = Vector2Int(3, 4)
            val visible = caster.calculateLineOfSight(origin, grid, null)
            visible.width shouldBe grid.width
        }

        it("should return a visibility grid with the same height as the tile grid") {
            val grid = floorGrid(7, 9)
            val origin = Vector2Int(3, 4)
            val visible = caster.calculateLineOfSight(origin, grid, null)
            visible.height shouldBe grid.height
        }
    }
})
