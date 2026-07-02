package com.sletmoe.korogue.algorithms.zonegen

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.Named
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * End-to-end acceptance for the prefab world-gen epic (krogue-b1p, ADR-0019): a "shack stamper"
 * that composes **all four** pieces —
 * - a variable-size [Prefab] factory (b1p.2),
 * - the [PrefabPlacer] scatter framework (b1p.3),
 * - registration as a [com.sletmoe.korogue.algorithms.zonegen.ZoneGenerator] via
 *   [GameModule.generators] (b1p.4), and
 * - materialization of its buffered entity spawns through [GameWorld] (b1p.1).
 *
 * Pure — no GL.
 */
class ShackStamperIntegrationTest : FunSpec({
    // Same instances flow legend -> Prefab -> stamped grid, so reference equality identifies them
    // (Tile is an open class with no structural equals — see SaveCodec's TileKey).
    val wall = Tile("wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)
    val floor = Tile("floor", '.', Color.LIGHT_GRAY, Color.BLACK, isWalkable = true, blocksLineOfSight = false)

    // A variable-size (3-5 x 3-5) shack: wall border, floor interior, one goblin on an interior cell.
    fun shack(random: Random): Prefab {
        val w = random.nextInt(3, 6)
        val h = random.nextInt(3, 6)
        val rows =
            (0 until h).map { y ->
                (0 until w).joinToString("") { x ->
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) "#" else "."
                }
            }
        return Prefab.builder(*rows.toTypedArray(), legend = mapOf('#' to wall, '.' to floor))
            .entity(1, 1, Named("goblin"))
            .build()
    }

    test("shack stamper scatters non-overlapping shacks and materializes their entities") {
        val placer = PrefabPlacer(targetCount = 5, minSpacing = 1)
        val stamper = ZoneGenerator { ctx -> placer.scatter(ctx) { r -> shack(r) } }
        val module = GameModule.engineDefaults().generator("shack-stamper", stamper).build()

        val world =
            GameWorld.create {
                zone("dungeon", width = 40, height = 40, isCurrentZone = true, random = Random(42)) {
                    addFeature(module.generators.resolve("shack-stamper"))
                }
            }

        // Each stamped shack materialized exactly one goblin (the b1p.1 buffered spawn sink).
        val goblins =
            world.ecs.entitiesWith<Named, Position, ZoneMember>()
                .filter { it.require<Named>().name == "goblin" && it.require<ZoneMember>().zoneId == "dungeon" }
                .toList()
        goblins.size shouldBeGreaterThan 0

        // Terrain was actually painted into the zone.
        var walls = 0
        for (y in 0 until 40) for (x in 0 until 40) if (world.currentZone.tiles[x, y] === wall) walls++
        walls shouldBeGreaterThan 0

        // Every goblin sits on its shack's interior floor: entity offsets align with the stamped
        // tiles (b1p.2 stamp translates tile + spawn by the same origin) and, since PrefabPlacer
        // placed the shacks without overlap, no other shack painted a wall over that cell.
        goblins.forEach { goblin ->
            val p = goblin.require<Position>()
            world.currentZone.tiles[p.x, p.y] shouldBe floor
        }
    }
})
