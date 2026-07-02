package com.sletmoe.korogue.algorithms.zonegen

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.utilities.Direction
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** A test-only marker component so we can assert prefab-supplied components survive to spawns. */
private data class PrefabTag(val label: String) : Component

private val WALL = Tile("Wall", '#', Color.WHITE, Color.BLACK, isWalkable = false, blocksLineOfSight = true)
private val FLOOR = Tile("Floor", '.', Color.WHITE, Color.BLACK, isWalkable = true, blocksLineOfSight = false)

private fun contextOf(
    width: Int,
    height: Int,
) = ZoneGenContext(Grid(width, height, BLANK_TILE), Random(1))

/**
 * Tests for the Prefab/room-stamp primitive (krogue-b1p.2, ADR-0019): a [Prefab] describes tiles
 * + independent entity placements relative to a local origin, and [Prefab.stamp] writes them into
 * a [ZoneGenContext] at a chosen offset. Pure — no GL.
 */
class PrefabTest : FunSpec({

    test("stamp paints the prefab's tiles at the origin") {
        val prefab =
            Prefab.builder(
                "###",
                "#.#",
                "###",
                legend = mapOf('#' to WALL, '.' to FLOOR),
            ).build()
        val ctx = contextOf(5, 5)

        prefab.stamp(ctx, 0, 0)

        ctx.tiles[0, 0] shouldBe WALL
        ctx.tiles[1, 0] shouldBe WALL
        ctx.tiles[1, 1] shouldBe FLOOR
        ctx.tiles[2, 2] shouldBe WALL
    }

    test("stamp translates tiles by the given origin") {
        val prefab =
            Prefab.builder(
                "##",
                ".#",
                legend = mapOf('#' to WALL, '.' to FLOOR),
            ).build()
        val ctx = contextOf(6, 6)

        prefab.stamp(ctx, 3, 2)

        ctx.tiles[3, 2] shouldBe WALL
        ctx.tiles[4, 2] shouldBe WALL
        ctx.tiles[3, 3] shouldBe FLOOR
        ctx.tiles[4, 3] shouldBe WALL
        // Untouched cells outside the stamped footprint keep the zone's original tile.
        ctx.tiles[0, 0] shouldBe BLANK_TILE
    }

    test("blank characters and short rows leave the underlying tile untouched") {
        val prefab =
            Prefab.builder(
                "##",
                "# ",
                legend = mapOf('#' to WALL),
            ).build()
        val ctx = contextOf(4, 4)
        ctx.tiles[1, 1] = FLOOR

        prefab.stamp(ctx, 0, 0)

        ctx.tiles[0, 0] shouldBe WALL
        ctx.tiles[1, 0] shouldBe WALL
        ctx.tiles[0, 1] shouldBe WALL
        // (1, 1) is a blank ' ' cell in the prefab: stamp must not overwrite it.
        ctx.tiles[1, 1] shouldBe FLOOR
    }

    test("stamp buffers a spawn per entity placement, translated by the origin") {
        val prefab =
            Prefab.builder(
                "...",
                legend = mapOf('.' to FLOOR),
            ).entity(1, 0, PrefabTag("goblin"))
                .build()
        val ctx = contextOf(8, 8)

        prefab.stamp(ctx, 4, 5)

        ctx.pendingSpawns shouldContainExactly listOf(SpawnRequest(5, 5, listOf(PrefabTag("goblin"))))
    }

    test("entity placements are independent of each other (no cross-references)") {
        val prefab =
            Prefab.builder(
                "..",
                legend = mapOf('.' to FLOOR),
            ).entity(0, 0, PrefabTag("chest"))
                .entity(1, 0, PrefabTag("key"))
                .build()
        val ctx = contextOf(5, 5)

        prefab.stamp(ctx, 0, 0)

        ctx.pendingSpawns shouldContainExactly
            listOf(
                SpawnRequest(0, 0, listOf(PrefabTag("chest"))),
                SpawnRequest(1, 0, listOf(PrefabTag("key"))),
            )
    }

    test("stamp fitting exactly at the zone's far edge succeeds") {
        val prefab =
            Prefab.builder(
                "##",
                "##",
                legend = mapOf('#' to WALL),
            ).build()
        val ctx = contextOf(4, 4)

        prefab.stamp(ctx, 2, 2)

        ctx.tiles[2, 2] shouldBe WALL
        ctx.tiles[3, 3] shouldBe WALL
    }

    test("stamp rejects an origin that would run off the east/south edge") {
        val prefab =
            Prefab.builder(
                "##",
                "##",
                legend = mapOf('#' to WALL),
            ).build()
        val ctx = contextOf(4, 4)

        shouldThrow<IllegalArgumentException> { prefab.stamp(ctx, 3, 0) }
        shouldThrow<IllegalArgumentException> { prefab.stamp(ctx, 0, 3) }
    }

    test("stamp rejects a negative origin") {
        val prefab =
            Prefab.builder(
                "#",
                legend = mapOf('#' to WALL),
            ).build()
        val ctx = contextOf(4, 4)

        shouldThrow<IllegalArgumentException> { prefab.stamp(ctx, -1, 0) }
    }

    test("builder rejects a character missing from the legend") {
        shouldThrow<IllegalArgumentException> {
            Prefab.builder(
                "#?#",
                legend = mapOf('#' to WALL),
            ).build()
        }
    }

    test("doors and anchors are carried as local-offset metadata, unused by stamp") {
        val prefab =
            Prefab.builder(
                "###",
                "#.#",
                "###",
                legend = mapOf('#' to WALL, '.' to FLOOR),
            ).door(1, 2, Direction.SOUTH)
                .anchor("center", 1, 1)
                .build()

        prefab.doors shouldContainExactly listOf(Door(1, 2, Direction.SOUTH))
        prefab.anchors shouldBe mapOf("center" to Vector2Int(1, 1))
    }

    test("a prefab rejects an entity/door/anchor offset outside its own bounds") {
        shouldThrow<IllegalArgumentException> {
            Prefab.builder("..", legend = mapOf('.' to FLOOR)).entity(5, 0, PrefabTag("oob")).build()
        }
        shouldThrow<IllegalArgumentException> {
            Prefab.builder("..", legend = mapOf('.' to FLOOR)).door(5, 0).build()
        }
        shouldThrow<IllegalArgumentException> {
            Prefab.builder("..", legend = mapOf('.' to FLOOR)).anchor("x", 5, 0).build()
        }
    }
})
