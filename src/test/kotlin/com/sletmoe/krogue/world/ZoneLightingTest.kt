package com.sletmoe.krogue.world

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.krogue.utilities.Grid
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Integration tests for [Zone.recalculateLightMap] — the lighting application that
 * 3b-1 wired into the kotile render path and Phase 3d verified. The per-distance
 * math is covered by LightValueCalculatorTest; this exercises the radius fill over
 * the grid and the blending of overlapping sources.
 */
class ZoneLightingTest : DescribeSpec({

    fun zoneOf(size: Int): Zone = Zone("test", Grid(size, size, BLANK_TILE))

    fun Zone.addLightAt(x: Int, y: Int, radius: Double): LightSource =
        LightSource(
            ZonalPosition(this, x, y),
            "torch",
            Color.WHITE,
            radius,
            DiminishingLightValueCalculator(),
        ).also { addLightSource(it) }

    describe("Zone.recalculateLightMap") {
        it("lights every cell within the source radius, including the boundary, and leaves the rest dark") {
            val zone = zoneOf(21)
            zone.addLightAt(10, 10, radius = 5.0)
            zone.recalculateLightMap()

            // Centre is lit.
            zone.lightMap[10, 10].shouldNotBeNull()

            // All four boundary tiles at exactly center ± radius are lit. The east/south
            // pair is the regression guard for the boundingBoxForCircle off-by-one: before
            // the fix these cells were never visited by forEachCoordinateInRadius.
            zone.lightMap[5, 10].shouldNotBeNull()   // west
            zone.lightMap[10, 5].shouldNotBeNull()   // north
            zone.lightMap[15, 10].shouldNotBeNull()  // east
            zone.lightMap[10, 15].shouldNotBeNull()  // south

            // Just outside the radius (distance 6 > 5) stays dark, as does a far corner.
            zone.lightMap[10, 16].shouldBeNull()
            zone.lightMap[0, 0].shouldBeNull()
        }

        it("produces intensity that falls off with distance from the source") {
            val zone = zoneOf(21)
            zone.addLightAt(10, 10, radius = 5.0)
            zone.recalculateLightMap()

            val centre = zone.lightMap[10, 10].shouldNotBeNull()
            val edge = zone.lightMap[15, 10].shouldNotBeNull()
            centre.intensity shouldBeGreaterThan edge.intensity
        }

        it("clears stale light when recalculated after a source is removed") {
            val zone = zoneOf(21)
            val light = zone.addLightAt(10, 10, radius = 5.0)
            zone.recalculateLightMap()
            zone.lightMap[10, 10].shouldNotBeNull()

            zone.removeLightSource(light)
            zone.recalculateLightMap()
            zone.lightMap[10, 10].shouldBeNull()
        }

        it("blends overlapping sources brighter than either alone (screen on intensity)") {
            val zone = zoneOf(21)
            zone.addLightAt(8, 10, radius = 5.0)
            zone.addLightAt(12, 10, radius = 5.0)
            zone.recalculateLightMap()

            // Cell (10,10) is distance 2 from each source. Single-source intensity at
            // distance 2: 1 - (2 - 0.5)/5 = 0.7. Screen blend: 1 - (1-0.7)^2 = 0.91.
            val blended = zone.lightMap[10, 10].shouldNotBeNull()
            blended.intensity shouldBe (0.91 plusOrMinus 1e-9)
            blended.intensity shouldBeGreaterThan 0.7
        }
    }
})
