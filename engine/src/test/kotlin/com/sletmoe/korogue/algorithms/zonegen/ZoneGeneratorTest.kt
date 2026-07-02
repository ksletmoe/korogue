package com.sletmoe.korogue.algorithms.zonegen

import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** A test-only marker component so we can assert generator-supplied components survive to the ECS. */
private data class Tag(val label: String) : Component

/**
 * Tests for the entity-aware world-gen seam (krogue-b1p.1, ADR-0019): a [ZoneGenerator] paints
 * terrain and buffers entity spawns onto a [ZoneGenContext]; [GameWorld.Builder] materializes them
 * once the ECS world exists. Pure — no GL.
 */
class ZoneGeneratorTest : FunSpec({

    test("ZoneGenContext buffers spawns without a World (pure generation)") {
        val ctx = ZoneGenContext(Grid(5, 5, BLANK_TILE), Random(1))
        val gen = ZoneGenerator { c -> c.spawn(2, 3, Tag("goblin")) }

        gen.generate(ctx)

        ctx.pendingSpawns shouldContainExactly listOf(SpawnRequest(2, 3, listOf(Tag("goblin"))))
    }

    test("GameWorld.Builder materializes buffered spawns with Position + ZoneMember") {
        val gen = ZoneGenerator { c -> c.spawn(2, 3, Tag("goblin")) }

        val world = GameWorld.create {
            zone("cave", width = 10, height = 10, isCurrentZone = true) {
                addFeature(gen)
            }
        }

        val entity = world.entityAt("cave", 2, 3)
        entity.shouldNotBeNull()
        entity.require<Position>() shouldBe Position(2, 3)
        entity.require<ZoneMember>().zoneId shouldBe "cave"
        entity.require<Tag>().label shouldBe "goblin"
    }

    test("a ZoneGenerator can both paint terrain and spawn entities") {
        val floor = BLANK_TILE
        val gen = ZoneGenerator { c ->
            c.tiles[1, 1] = floor
            c.spawn(1, 1, Tag("torch"))
        }

        val world = GameWorld.create {
            zone("room", width = 4, height = 4, isCurrentZone = true) { addFeature(gen) }
        }

        world.currentZone.tiles[1, 1] shouldBe floor
        world.entityAt("room", 1, 1).shouldNotBeNull()
    }

    test("terrain-only generation buffers no spawns") {
        val builder = com.sletmoe.korogue.world.Zone.Builder("t", 6, 6, Random(1))
        builder.addFeature(randomWalkCave(3, 3, length = 20, groundTile = BLANK_TILE))
        builder.pendingSpawns shouldBe emptyList()
    }
})
