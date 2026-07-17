package com.sletmoe.korogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Grid
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

    val calculators = GameModule.engineDefaults().build().calculators

    fun setup(size: Int): Pair<World, Zone> {
        val zone = Zone("test", Grid(size, size, BLANK_TILE))
        val gw = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
        return gw.ecs.addSystem(LightingSystem(gw, calculators)) to zone
    }

    fun World.addLightAt(
        x: Int,
        y: Int,
        radius: Double,
    ): EntityId =
        spawn(
            Position(x, y),
            ZoneMember("test"),
            LightEmitter(Color.WHITE.toNormalizedRgb(), radius, DiminishingLightValueCalculator.ID),
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

        it("with a fullbright ambient, every cell is lit even with no emitters (global illumination)") {
            val zone = Zone("test", Grid(21, 21, BLANK_TILE))
            val gw = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
            val world = gw.ecs.addSystem(LightingSystem(gw, calculators, ambientLight = LightValue.FULLBRIGHT))

            world.tick()

            // No LightEmitter anywhere, yet the whole map is lit at full intensity.
            zone.lightMap[0, 0].shouldNotBeNull().intensity shouldBe 1.0
            zone.lightMap[20, 20].shouldNotBeNull().intensity shouldBe 1.0
            zone.lightMap[10, 10].shouldNotBeNull().intensity shouldBe 1.0
        }

        it("ambient is a baseline emitters still blend on top of") {
            val zone = Zone("test", Grid(21, 21, BLANK_TILE))
            val gw = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
            val world =
                gw.ecs.addSystem(
                    LightingSystem(
                        gw,
                        calculators,
                        // A dim ambient so an emitter visibly raises intensity above it.
                        ambientLight = LightValue(Color.WHITE.toNormalizedRgb(), 0.2),
                    ),
                )
            world.addLightAt(10, 10, radius = 5.0)

            world.tick()

            // A far corner sits at the ambient baseline; near the emitter it's brighter.
            zone.lightMap[0, 0].shouldNotBeNull().intensity shouldBe (0.2 plusOrMinus 1e-9)
            zone.lightMap[10, 10].shouldNotBeNull().intensity shouldBeGreaterThan 0.2
        }

        it("only recomputes active zones; a dormant zone keeps its prior light (ADR-0008)") {
            val active = Zone("active", Grid(21, 21, BLANK_TILE))
            val dormant = Zone("dormant", Grid(21, 21, BLANK_TILE))
            val gw =
                GameWorld(
                    World(),
                    mapOf(active.zoneId to active, dormant.zoneId to dormant),
                    // Scope comes from the world's policy (krogue-cjv); the default CurrentZoneOnly
                    // makes "active" the only simulated zone.
                    "active",
                )
            val world = gw.ecs.addSystem(LightingSystem(gw, calculators))

            fun World.lightIn(
                zoneId: String,
                x: Int,
                y: Int,
            ) = spawn(
                Position(x, y),
                ZoneMember(zoneId),
                LightEmitter(Color.WHITE.toNormalizedRgb(), 5.0, DiminishingLightValueCalculator.ID),
            )
            world.lightIn("active", 10, 10)
            world.lightIn("dormant", 10, 10)

            world.tick()

            active.lightMap[10, 10].shouldNotBeNull()
            dormant.lightMap[10, 10].shouldBeNull() // dormant zone never simulated
        }
    }
})
