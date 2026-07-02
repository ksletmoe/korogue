package com.sletmoe.korogue.algorithms.zonegen

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private val WALL = Tile("Wall", '#', Color.WHITE, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

private fun contextOf(
    width: Int,
    height: Int,
    seed: Long = 1,
) = ZoneGenContext(Grid(width, height, BLANK_TILE), Random(seed))

/** A fixed-size 3x3 solid-wall prefab, the simplest possible stamp target for these tests. */
private fun box3x3(@Suppress("UNUSED_PARAMETER") random: Random): Prefab =
    Prefab.builder("###", "###", "###", legend = mapOf('#' to WALL)).build()

/** A factory producing a variable-size (2-5 x 2-5) solid-wall prefab, per ADR-0019's shack case. */
private fun variableBox(random: Random): Prefab {
    val w = random.nextInt(2, 6)
    val h = random.nextInt(2, 6)
    val rows = List(h) { "#".repeat(w) }
    return Prefab.builder(*rows.toTypedArray(), legend = mapOf('#' to WALL)).build()
}

/**
 * Tests for the placement framework (krogue-b1p.3, ADR-0019): [PrefabPlacer.scatter] finds
 * non-colliding, in-bounds origins for a prefab factory and stamps each one, giving up gracefully
 * once its attempt budget is exhausted. Pure — no GL.
 */
class PrefabPlacerTest : FunSpec({

    test("scatter places the requested count when there's plenty of room") {
        val ctx = contextOf(30, 30)

        val placed = PrefabPlacer(targetCount = 5, minSpacing = 1).scatter(ctx, ::box3x3)

        placed shouldHaveSize 5
    }

    test("scatter actually stamps a prefab at each returned rect") {
        val ctx = contextOf(10, 10)

        val placed = PrefabPlacer(targetCount = 1).scatter(ctx, ::box3x3)

        val rect = placed.single()
        ctx.tiles[rect.x, rect.y] shouldBe WALL
        ctx.tiles[rect.x + 2, rect.y + 2] shouldBe WALL
    }

    test("no two placements overlap") {
        val ctx = contextOf(20, 20)

        val placed = PrefabPlacer(targetCount = 10, minSpacing = 0, maxAttempts = 500).scatter(ctx, ::box3x3)

        for (i in placed.indices) {
            for (j in placed.indices) {
                if (i == j) continue
                placed[i].overlaps(placed[j]) shouldBe false
            }
        }
    }

    test("placements respect minSpacing, not just non-overlap") {
        val ctx = contextOf(20, 20)
        val minSpacing = 2

        val placed = PrefabPlacer(targetCount = 8, minSpacing = minSpacing, maxAttempts = 500).scatter(ctx, ::box3x3)

        for (i in placed.indices) {
            for (j in placed.indices) {
                if (i == j) continue
                // overlaps(padding = minSpacing) is exactly "closer together than minSpacing allows".
                placed[i].overlaps(placed[j], padding = minSpacing) shouldBe false
            }
        }
    }

    test("all placements are fully within the zone's bounds") {
        val ctx = contextOf(15, 12)

        val placed = PrefabPlacer(targetCount = 6, maxAttempts = 200).scatter(ctx, ::variableBox)

        placed.forEach { rect ->
            (rect.x >= 0) shouldBe true
            (rect.y >= 0) shouldBe true
            (rect.x + rect.width) shouldBeLessThanOrEqual ctx.tiles.width
            (rect.y + rect.height) shouldBeLessThanOrEqual ctx.tiles.height
        }
    }

    test("a factory producing variable-size prefabs is bounds-fit and collision-checked per attempt") {
        val ctx = contextOf(25, 25)

        val placed = PrefabPlacer(targetCount = 6, minSpacing = 1, maxAttempts = 300).scatter(ctx, ::variableBox)

        placed shouldHaveSize 6
        for (i in placed.indices) {
            for (j in placed.indices) {
                if (i == j) continue
                placed[i].overlaps(placed[j], padding = 1) shouldBe false
            }
        }
    }

    test("scatter gives up gracefully, without throwing, when the zone is too small to fit anything") {
        val ctx = contextOf(2, 2)

        shouldNotThrowAny {
            val placed = PrefabPlacer(targetCount = 3, maxAttempts = 20).scatter(ctx, ::box3x3)
            placed shouldHaveSize 0
        }
    }

    test("scatter gives up gracefully once spacing makes further placements impossible") {
        val ctx = contextOf(6, 6)

        // A 6x6 zone with minSpacing = 10 can fit at most one 3x3 prefab before every further
        // candidate collides with it.
        val placed = PrefabPlacer(targetCount = 5, minSpacing = 10, maxAttempts = 50).scatter(ctx, ::box3x3)

        placed shouldHaveSize 1
    }

    test("scatter never exceeds targetCount") {
        val ctx = contextOf(40, 40)

        val placed = PrefabPlacer(targetCount = 4, maxAttempts = 1000).scatter(ctx, ::box3x3)

        placed shouldHaveSize 4
    }

    test("scatter with targetCount 0 places nothing") {
        val ctx = contextOf(10, 10)

        val placed = PrefabPlacer(targetCount = 0).scatter(ctx, ::box3x3)

        placed shouldHaveSize 0
    }

    test("scatter is deterministic for a given seed") {
        val factory = ::variableBox

        val placedA = PrefabPlacer(targetCount = 6, minSpacing = 1, maxAttempts = 200).scatter(contextOf(20, 20, seed = 42), factory)
        val placedB = PrefabPlacer(targetCount = 6, minSpacing = 1, maxAttempts = 200).scatter(contextOf(20, 20, seed = 42), factory)

        placedA shouldBe placedB
    }

    test("rejects a negative targetCount, minSpacing, or maxAttempts") {
        shouldThrow<IllegalArgumentException> { PrefabPlacer(targetCount = -1) }
        shouldThrow<IllegalArgumentException> { PrefabPlacer(targetCount = 1, minSpacing = -1) }
        shouldThrow<IllegalArgumentException> { PrefabPlacer(targetCount = 1, maxAttempts = -1) }
    }
})
