package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.LightFlicker
import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * MapPanel renders a chosen observer's [Perceived] (ADR-0015): a cell is drawn when the observer
 * perceives it, an occupant when the observer perceives *that entity* — the panel no longer computes
 * LOS or lighting itself. These tests drive it by writing a `Perceived` directly onto the player
 * (the default observer), the same component `PerceptionSystem` caches each tick at runtime.
 */
class MapPanelTest : FunSpec({

    val floor = Tile("floor", '.', Color.GRAY, Color.BLACK, isWalkable = true, blocksLineOfSight = false)

    fun cell(
        x: Int,
        y: Int,
    ) = Vector2Int(x, y)

    /**
     * A 6x6 all-floor zone with a player at (2, 2) carrying a [Perceived] of [perceives] cells and
     * [perceivesEntities]; [lit] cells get a light so a perceived cell there renders tinted.
     */
    fun world(
        perceives: Set<Vector2Int> = emptySet(),
        perceivesEntities: Set<EntityId> = emptySet(),
        lit: Set<Vector2Int> = emptySet(),
    ): Pair<GameWorld, EntityId> {
        val gw = GameWorld.create { zone("z", 6, 6, isCurrentZone = true) { fill(floor) } }
        val player =
            gw.ecs
                .spawn(
                    Position(2, 2),
                    ZoneMember("z"),
                    Renderable('@', Color.YELLOW.toNormalizedRgb(), RenderLayer.PLAYER),
                    Player,
                    Perceived("z", perceives, perceivesEntities),
                ).id
        lit.forEach { gw.currentZone.lightMap[it.x, it.y] = LightValue(Color.WHITE.toNormalizedRgb(), 1.0) }
        return gw to player
    }

    /** Spawns a creature `m` at ([x], [y]) in zone "z"; returns its id. */
    fun GameWorld.spawnMonster(
        x: Int,
        y: Int,
    ): EntityId =
        ecs
            .spawn(
                Position(x, y),
                ZoneMember("z"),
                Renderable('m', Color.RED.toNormalizedRgb(), RenderLayer.CREATURE),
            ).id

    fun panel(
        gw: GameWorld,
        fog: Grid<Boolean> = Grid(6, 6, false),
        decorate: (TileSurface, MapCamera) -> Unit = { _, _ -> },
        elapsedMsProvider: () -> Long = { 0L },
    ) = MapPanel(IntRect(0, 0, 6, 6), gw, { fog }, decorate = decorate, elapsedMsProvider = elapsedMsProvider)

    test("the observer is always drawn at its camera-centred cell, even perceiving nothing") {
        val surface = RecordingSurface(6, 6)

        panel(world().first).draw(surface)

        val drawn = surface.top(2, 2).shouldNotBeNull()
        drawn.glyph shouldBe '@'
        drawn.z shouldBe RenderLayer.PLAYER.zIndex
    }

    test("decorate runs after terrain/occupants, with the same camera the panel just drew") {
        val surface = RecordingSurface(6, 6)
        var capturedCamera: MapCamera? = null
        var puttsAtDecorateTime = -1

        panel(
            world().first,
            decorate = { s, camera ->
                capturedCamera = camera
                puttsAtDecorateTime = (s as RecordingSurface).puts.size
            },
        ).draw(surface)

        capturedCamera.shouldNotBeNull()
        // The observer is at (2, 2), centred in a 6x6 viewport on a 6x6 zone -> origin (0, 0).
        capturedCamera.screenX(2) shouldBe 2
        capturedCamera.screenY(2) shouldBe 2
        // decorate saw the writes terrain/occupants already made this frame (ran after them).
        (puttsAtDecorateTime > 0) shouldBe true
    }

    test("terrain is drawn only where the observer perceives it") {
        val surface = RecordingSurface(6, 6)

        panel(world(perceives = setOf(cell(3, 3)), lit = setOf(cell(3, 3))).first).draw(surface)

        surface.top(3, 3).shouldNotBeNull().glyph shouldBe '.' // perceived (and lit) floor shown
        surface.top(0, 0).shouldBeNull() // not perceived, never seen -> hidden (left black)
    }

    test("a perceived but unlit cell is still drawn (e.g. darkvision), untinted") {
        val surface = RecordingSurface(6, 6)

        panel(world(perceives = setOf(cell(3, 3))).first).draw(surface) // perceived, no light

        val drawn = surface.top(3, 3).shouldNotBeNull()
        drawn.glyph shouldBe '.'
        drawn.fg shouldBe floor.color // no light map entry -> tile color used as-is
    }

    test("an occupant is drawn only when the observer perceives that entity") {
        val cells = setOf(cell(4, 4))

        // Perceived as an entity (and on a perceived cell): drawn.
        val seenSurface = RecordingSurface(6, 6)
        val seen = world(perceives = cells, lit = cells)
        val monster = seen.first.spawnMonster(4, 4)
        seen.first.ecs.set(seen.second, Perceived("z", cells, setOf(monster)))
        panel(seen.first).draw(seenSurface)
        seenSurface.top(4, 4).shouldNotBeNull().glyph shouldBe 'm'

        // On a perceived cell but NOT perceived as an entity (invisible): the cell's terrain shows
        // through, but the creature is not drawn.
        val hiddenSurface = RecordingSurface(6, 6)
        val hidden = world(perceives = cells, lit = cells)
        hidden.first.spawnMonster(4, 4)
        panel(hidden.first).draw(hiddenSurface)
        hiddenSurface.top(4, 4).shouldNotBeNull().glyph shouldBe '.' // terrain, not the hidden 'm'
    }

    test("an entity perceived off any perceived cell is drawn (e.g. telepathy through a wall)") {
        val surface = RecordingSurface(6, 6)
        val (gw, player) = world() // perceives no cells
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", emptySet(), setOf(monster)))

        panel(gw).draw(surface)

        surface.top(4, 4).shouldNotBeNull().glyph shouldBe 'm'
    }

    test("a Perceived cached for another zone reveals nothing here") {
        val surface = RecordingSurface(6, 6)
        val (gw, player) = world(lit = setOf(cell(3, 3)))
        gw.ecs.set(player, Perceived("other-zone", setOf(cell(3, 3))))

        panel(gw).draw(surface)

        surface.top(3, 3).shouldBeNull() // stale cross-zone cache ignored
    }

    test("previously-seen (fog) terrain is drawn even when not currently perceived") {
        val surface = RecordingSurface(6, 6)
        val fog = Grid(6, 6, false).apply { this[1, 1] = true }

        panel(world().first, fog).draw(surface)

        val drawn = surface.top(1, 1).shouldNotBeNull()
        drawn.glyph shouldBe '.'
        drawn.bg shouldBe Color.BLACK // remembered cells use a black background
    }

    test("perceived cells are accumulated into the fog grid in place") {
        val fog = Grid(6, 6, false)

        panel(world(perceives = setOf(cell(4, 4))).first, fog).draw(RecordingSurface(6, 6))

        fog[4, 4] shouldBe true // now remembered
        fog[0, 0] shouldBe false // never perceived
    }

    test("the default remembered look is unchanged (dim + blue-boost on black)") {
        val surface = RecordingSurface(6, 6)
        val fog = Grid(6, 6, false).apply { this[1, 1] = true }

        panel(world().first, fog).draw(surface) // default rememberedRenderer

        val expected = MapPanel.dimmedRememberedRenderer()(floor) // the built-in look, applied to floor
        val drawn = surface.top(1, 1).shouldNotBeNull()
        drawn.fg shouldBe expected.fg
        drawn.bg shouldBe expected.bg
    }

    test("a custom rememberedRenderer controls remembered-cell glyph and colors") {
        val surface = RecordingSurface(6, 6)
        val fog = Grid(6, 6, false).apply { this[1, 1] = true }
        val map =
            MapPanel(IntRect(0, 0, 6, 6), world().first, { fog }, rememberedRenderer = {
                MapPanel.RenderedCell('?', Color.RED, Color.BLUE)
            })

        map.draw(surface)

        val drawn = surface.top(1, 1).shouldNotBeNull()
        drawn.glyph shouldBe '?'
        drawn.fg shouldBe Color.RED
        drawn.bg shouldBe Color.BLUE
    }

    test("a rememberedRenderer returning null leaves the remembered cell undrawn") {
        val surface = RecordingSurface(6, 6)
        val fog = Grid(6, 6, false).apply { this[1, 1] = true }
        val map = MapPanel(IntRect(0, 0, 6, 6), world().first, { fog }, rememberedRenderer = { null })

        map.draw(surface)

        surface.top(1, 1).shouldBeNull() // suppressed -> left black, like a never-seen cell
    }

    test("a custom occupantRenderer remaps a perceived occupant's glyph and foreground") {
        val surface = RecordingSurface(6, 6)
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells, lit = cells)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))
        val map =
            MapPanel(IntRect(0, 0, 6, 6), gw, { Grid(6, 6, false) }, occupantRenderer = {
                MapPanel.RenderedGlyph('X', Color.GREEN)
            })

        map.draw(surface)

        val drawn = surface.top(4, 4).shouldNotBeNull()
        drawn.glyph shouldBe 'X'
        drawn.fg shouldBe Color.GREEN
        drawn.bg shouldBe Color.BLACK // background stays engine-computed (the seam carries no bg)
    }

    test("the occupantRenderer is told the entity, its renderable, the tile, and that it is perceived") {
        val surface = RecordingSurface(6, 6)
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells, lit = cells)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))
        var seen: MapPanel.OccupantRender? = null
        val map =
            MapPanel(IntRect(0, 0, 6, 6), gw, { Grid(6, 6, false) }, occupantRenderer = { ctx ->
                if (ctx.entity.id == monster) seen = ctx
                MapPanel.RenderedGlyph(ctx.renderable.glyph, ctx.renderable.color.toColor())
            })

        map.draw(surface)

        val ctx = seen.shouldNotBeNull()
        ctx.renderable.glyph shouldBe 'm'
        ctx.tile shouldBe floor
        ctx.perceived shouldBe true
    }

    test("an occupantRenderer returning null suppresses the occupant, leaving terrain showing") {
        val surface = RecordingSurface(6, 6)
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells, lit = cells)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))
        val map = MapPanel(IntRect(0, 0, 6, 6), gw, { Grid(6, 6, false) }, occupantRenderer = { null })

        map.draw(surface)

        surface.top(4, 4).shouldNotBeNull().glyph shouldBe '.' // occupant suppressed -> perceived terrain shows
    }

    test("the default occupant look draws the occupant's own renderable unchanged") {
        val surface = RecordingSurface(6, 6)
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells, lit = cells)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))

        panel(gw).draw(surface) // default occupantRenderer

        val drawn = surface.top(4, 4).shouldNotBeNull()
        drawn.glyph shouldBe 'm'
        // world(lit=...)'s light is WHITE at full intensity 1.0 -- tintedByLight is then an
        // identity multiply, so this doesn't yet prove fg is tinted at all (see the dim-light
        // test below for that); it only proves full-intensity white light doesn't discolor it.
        drawn.fg shouldBe Color.RED // the monster's Renderable color
    }

    test("an occupant's foreground is tinted by the lit cell's light, not its full Renderable color (krogue-0w3)") {
        val surface = RecordingSurface(6, 6)
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells) // no light yet -- set a dim one below
        gw.currentZone.lightMap[4, 4] = LightValue(Color.WHITE.toNormalizedRgb(), 0.3) // dim: intensity < 1.0
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))

        panel(gw).draw(surface)

        val drawn = surface.top(4, 4).shouldNotBeNull()
        drawn.fg shouldNotBe Color.RED // dimmed, not the monster's full undimmed Renderable color
        (drawn.fg.r < Color.RED.r) shouldBe true
    }

    // -------------------------------------------------------------------------
    // Light flicker (krogue-ncl)
    // -------------------------------------------------------------------------

    /** Spawns a [LightEmitter] with [flicker] at ([x], [y]) in zone "z"; returns its id. */
    fun GameWorld.spawnFlickeringLight(
        x: Int,
        y: Int,
        flicker: LightFlicker,
        radius: Double = 5.0,
    ): EntityId =
        ecs
            .spawn(
                Position(x, y),
                ZoneMember("z"),
                LightEmitter(Color.WHITE.toNormalizedRgb(), radius, "diminishing", flicker),
            ).id

    test("a flickering emitter modulates the lit cell's intensity over wall-clock time") {
        val cells = setOf(cell(4, 4))
        val (gw, _) = world(perceives = cells, lit = cells)
        gw.spawnFlickeringLight(4, 4, LightFlicker(amplitude = 0.5, periodMs = 1000, seed = 0))

        val steady = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 0L }).draw(steady) // factor = 1.0 (unmodulated)

        val dimmed = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 750L }).draw(dimmed) // 3/4 period -> trough, factor = 0.5

        val steadyFg = steady.top(4, 4).shouldNotBeNull().fg
        val dimmedFg = dimmed.top(4, 4).shouldNotBeNull().fg
        dimmedFg shouldNotBe steadyFg
        (dimmedFg.r < steadyFg.r) shouldBe true
    }

    test("with no flicker-configured emitter, intensity is unaffected by elapsedMs (unchanged behavior)") {
        val cells = setOf(cell(4, 4))
        val (gw, _) = world(perceives = cells, lit = cells) // lit directly, no LightEmitter at all

        val atZero = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 0L }).draw(atZero)

        val atLater = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 999_999L }).draw(atLater)

        atZero.top(4, 4).shouldNotBeNull().fg shouldBe atLater.top(4, 4).shouldNotBeNull().fg
    }

    test("flicker only reaches cells within the emitter's own radius") {
        val cells = setOf(cell(0, 0), cell(4, 4))
        val (gw, _) = world(perceives = cells, lit = cells)
        // A small-radius flickering torch at (0,0) cannot reach (4,4) (distance ~5.66).
        gw.spawnFlickeringLight(0, 0, LightFlicker(amplitude = 0.9, periodMs = 1000), radius = 1.0)

        val atZero = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 0L }).draw(atZero)

        val atTrough = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 750L }).draw(atTrough)

        // (4,4) is lit independently of the far-away torch, so it stays steady regardless of time.
        atZero.top(4, 4).shouldNotBeNull().fg shouldBe atTrough.top(4, 4).shouldNotBeNull().fg
    }

    test("an occupant's background flickers along with the terrain beneath it") {
        // A non-black background: BLACK * anything is still BLACK, which would hide the effect.
        val litFloor = Tile("lit-floor", '.', Color.GRAY, Color.NAVY, isWalkable = true, blocksLineOfSight = false)
        val gw = GameWorld.create { zone("z", 6, 6, isCurrentZone = true) { fill(litFloor) } }
        val player =
            gw.ecs
                .spawn(
                    Position(2, 2),
                    ZoneMember("z"),
                    Renderable('@', Color.YELLOW.toNormalizedRgb(), RenderLayer.PLAYER),
                    Player,
                    Perceived("z", setOf(cell(4, 4)), emptySet()),
                ).id
        gw.currentZone.lightMap[4, 4] = LightValue(Color.WHITE.toNormalizedRgb(), 1.0)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", setOf(cell(4, 4)), setOf(monster)))
        gw.spawnFlickeringLight(4, 4, LightFlicker(amplitude = 0.5, periodMs = 1000))

        val steady = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 0L }).draw(steady)

        val dimmed = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 750L }).draw(dimmed)

        val steadyBg = steady.top(4, 4).shouldNotBeNull().bg
        val dimmedBg = dimmed.top(4, 4).shouldNotBeNull().bg
        dimmedBg shouldNotBe steadyBg
    }

    test("an occupant's foreground flickers along with its background (krogue-0w3)") {
        val cells = setOf(cell(4, 4))
        val (gw, player) = world(perceives = cells, lit = cells)
        val monster = gw.spawnMonster(4, 4)
        gw.ecs.set(player, Perceived("z", cells, setOf(monster)))
        gw.spawnFlickeringLight(4, 4, LightFlicker(amplitude = 0.5, periodMs = 1000))

        val steady = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 0L }).draw(steady)

        val dimmed = RecordingSurface(6, 6)
        panel(gw, elapsedMsProvider = { 750L }).draw(dimmed)

        val steadyFg = steady.top(4, 4).shouldNotBeNull().fg
        val dimmedFg = dimmed.top(4, 4).shouldNotBeNull().fg
        dimmedFg shouldNotBe steadyFg
        (dimmedFg.r < steadyFg.r) shouldBe true
    }
})
