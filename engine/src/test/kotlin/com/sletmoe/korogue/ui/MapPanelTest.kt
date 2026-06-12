package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.korogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class MapPanelTest : FunSpec({

    val floor = Tile("floor", '.', Color.GRAY, Color.BLACK, isWalkable = true, blocksLineOfSight = false)

    /** A 6x6 all-floor zone with a player at (2, 2); [lit] cells get a light so they're visible. */
    fun world(vararg lit: Pair<Int, Int>): GameWorld {
        val gw = GameWorld.create { zone("z", 6, 6, isCurrentZone = true) { fill(floor) } }
        gw.ecs.spawn(
            Position(2, 2),
            ZoneMember("z"),
            Renderable('@', Color.YELLOW.toNormalizedRgb(), RenderLayer.PLAYER),
            Player,
        )
        lit.forEach { (x, y) -> gw.currentZone.lightMap[x, y] = LightValue(Color.WHITE.toNormalizedRgb(), 1.0) }
        return gw
    }

    fun panel(
        gw: GameWorld,
        fog: Grid<Boolean> = Grid(6, 6, false),
    ) = MapPanel(IntRect(0, 0, 6, 6), gw, { fog }, OmnicientLineOfSightCalculator())

    test("the player is always drawn at its camera-centred cell, even unlit") {
        val surface = RecordingSurface(6, 6)

        panel(world()).draw(surface)

        val cell = surface.top(2, 2).shouldNotBeNull()
        cell.glyph shouldBe '@'
        cell.z shouldBe RenderLayer.PLAYER.zIndex
    }

    test("terrain is drawn only where currently visible (lit)") {
        val surface = RecordingSurface(6, 6)

        panel(world(lit = arrayOf(3 to 3))).draw(surface)

        surface.top(3, 3).shouldNotBeNull().glyph shouldBe '.' // lit floor shown
        surface.top(0, 0).shouldBeNull() // unlit, never seen -> hidden (left black)
    }

    test("maximumVisibilityDistance caps how far is seen; null is uncapped (walls only)") {
        // Player at (2,2); a far lit cell at (5,5), distance ~4.24. The zone is open (no walls
        // between), so SymmetricShadowCaster reveals (5,5) unless the distance cap hides it.
        fun panelWith(distance: Double?) =
            MapPanel(
                IntRect(0, 0, 6, 6),
                world(lit = arrayOf(5 to 5)),
                { Grid(6, 6, false) },
                SymmetricShadowCaster(),
                maximumVisibilityDistance = distance,
            )

        val capped = RecordingSurface(6, 6)
        panelWith(2.0).draw(capped)
        capped.top(5, 5).shouldBeNull() // lit, but beyond the 2-tile cap -> not seen

        val uncapped = RecordingSurface(6, 6)
        panelWith(null).draw(uncapped)
        uncapped.top(5, 5).shouldNotBeNull().glyph shouldBe '.' // no cap, no walls -> seen
    }

    test("previously-seen (fog) terrain is drawn even when not currently lit") {
        val surface = RecordingSurface(6, 6)
        val fog = Grid(6, 6, false).apply { this[1, 1] = true }

        panel(world(), fog).draw(surface)

        val cell = surface.top(1, 1).shouldNotBeNull()
        cell.glyph shouldBe '.'
        cell.bg shouldBe Color.BLACK // remembered cells use a black background
    }

    test("visible cells are accumulated into the fog grid in place") {
        val fog = Grid(6, 6, false)

        panel(world(lit = arrayOf(4 to 4)), fog).draw(RecordingSurface(6, 6))

        fog[4, 4] shouldBe true // now remembered
        fog[0, 0] shouldBe false // never visible
    }
})
