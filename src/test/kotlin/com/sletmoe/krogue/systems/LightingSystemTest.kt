package com.sletmoe.krogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.lighting.LightCalculators
import com.sletmoe.krogue.components.LightEmitter
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.EntityId
import com.sletmoe.krogue.ecs.World
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.BLANK_TILE
import com.sletmoe.krogue.world.Zone
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Integration tests for [LightingSystem] — the query-driven replacement for the old
 * `Zone.recalculateLightMap` (4b-s5). The per-distance math is covered by
 * LightValueCalculatorTest; this exercises the radius fill over the grid and the
 * blending of overlapping emitters. Assertions carry over from the former
 * ZoneLightingTest, including the boundingBoxForCircle off-by-one regression guard.
 */
class LightingSystemTest : DescribeSpec({

    fun setup(size: Int): Pair<World, Zone> {
        val zone = Zone("test", Grid(size, size, BLANK_TILE))
        val world = World().addSystem(LightingSystem(mapOf(zone.zoneId to zone)))
        return world to zone
    }

    fun World.addLightAt(
        x: Int,
        y: Int,
        radius: Double,
    ): EntityId =
        spawn(
            Position(x, y),
            ZoneMember("test"),
            LightEmitter(Color.WHITE.toNormalizedRgb(), radius, LightCalculators.DIMINISHING),
        ).id

    describe("LightingSystem") {
        it("lights every cell within the emitter radius, including the boundary, and leaves the rest dark") {
            val (world, zone) = setup(21)
            world.addLightAt(10, 10, radius = 5.0)
            world.tick()

            // Centre is lit.
            zone.lightMap[10, 10].shouldNotBeNull()

            // All four boundary tiles at exactly center ± radius are lit. The east/south
            // pair is the regression guard for the boundingBoxForCircle off-by-one: before
            // the fix these cells were never visited by forEachCoordinateInRadius.
            zone.lightMap[5, 10].shouldNotBeNull() // west
            zone.lightMap[10, 5].shouldNotBeNull() // north
            zone.lightMap[15, 10].shouldNotBeNull() // east
            zone.lightMap[10, 15].shouldNotBeNull() // south

            // Just outside the radius (distance 6 > 5) stays dark, as does a far corner.
            zone.lightMap[10, 16].shouldBeNull()
            zone.lightMap[0, 0].shouldBeNull()
        }

        it("produces intensity that falls off with distance from the source") {
            val (world, zone) = setup(21)
            world.addLightAt(10, 10, radius = 5.0)
            world.tick()

            val centre = zone.lightMap[10, 10].shouldNotBeNull()
            val edge = zone.lightMap[15, 10].shouldNotBeNull()
            centre.intensity shouldBeGreaterThan edge.intensity
        }

        it("clears stale light when the emitter is removed and the system re-runs") {
            val (world, zone) = setup(21)
            val light = world.addLightAt(10, 10, radius = 5.0)
            world.tick()
            zone.lightMap[10, 10].shouldNotBeNull()

            world.despawn(light)
            world.tick()
            zone.lightMap[10, 10].shouldBeNull()
        }

        it("blends overlapping emitters brighter than either alone (screen on intensity)") {
            val (world, zone) = setup(21)
            world.addLightAt(8, 10, radius = 5.0)
            world.addLightAt(12, 10, radius = 5.0)
            world.tick()

            // Cell (10,10) is distance 2 from each source. Single-source intensity at
            // distance 2: 1 - (2 - 0.5)/5 = 0.7. Screen blend: 1 - (1-0.7)^2 = 0.91.
            val blended = zone.lightMap[10, 10].shouldNotBeNull()
            blended.intensity shouldBe (0.91 plusOrMinus 1e-9)
            blended.intensity shouldBeGreaterThan 0.7
        }

        it("only recomputes active zones; a dormant zone keeps its prior light (ADR-0008)") {
            val active = Zone("active", Grid(21, 21, BLANK_TILE))
            val dormant = Zone("dormant", Grid(21, 21, BLANK_TILE))
            val world =
                World().addSystem(
                    LightingSystem(
                        mapOf(active.zoneId to active, dormant.zoneId to dormant),
                        activeZones = { setOf("active") },
                    ),
                )

            fun World.lightIn(
                zoneId: String,
                x: Int,
                y: Int,
            ) = spawn(
                Position(x, y),
                ZoneMember(zoneId),
                LightEmitter(Color.WHITE.toNormalizedRgb(), 5.0, LightCalculators.DIMINISHING),
            )
            world.lightIn("active", 10, 10)
            world.lightIn("dormant", 10, 10)

            world.tick()

            active.lightMap[10, 10].shouldNotBeNull()
            dormant.lightMap[10, 10].shouldBeNull() // dormant zone never simulated
        }
    }
})
