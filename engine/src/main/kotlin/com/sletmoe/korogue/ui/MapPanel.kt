package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone

/**
 * The map as a [Widget] (ADR-0011): draws [GameWorld]'s current zone — terrain (with lighting
 * tints + previously-seen dimming) and the occupying entities — into its [bounds] on the supplied
 * [TileSurface]. Replaces the old window-blitting `KotileZoneRenderer`: instead of building a
 * zone-sized tilemap and viewporting the whole window, it samples an observer-centred viewport
 * sized to its own pane and writes visible cells directly, so it composes with other panels in the
 * one UI render pass.
 *
 * **It renders a chosen observer's [Perceived] and nothing more** (ADR-0015): it no longer computes
 * `LOS ∧ lit` or owns a view distance (that policy moved to the perception layer — senses on the
 * observer, capped per-sense, superseding the interim `maximumVisibilityDistance` of krogue-f50). A
 * cell is drawn when the observer perceives it; an occupant when the observer perceives *that
 * entity* — so an invisible monster standing on a lit cell is correctly hidden, and a tremor-sensed
 * creature behind a wall is correctly shown. Which observer the camera follows is a presentation
 * choice ([observer], the player by default), so spectating a companion or per-faction fog-of-war
 * fall out for free.
 *
 * The observer's `Perceived` is the per-tick cache written by `PerceptionSystem`; if the observer
 * carries none (or it is for a different zone) the map shows only the observer and remembered
 * (fogged) terrain. Because it reads `gameWorld.currentZone` and the observer every frame, a zone
 * transition needs no renderer rebuild. Fog-of-war memory is fetched per zone via [fogFor]
 * (krogue-ro8) and accumulated in place.
 *
 * Layers (local z): terrain 0, then occupants by [Renderable.layer]'s `zIndex` (creature 1,
 * player 2). The observer is always drawn (it is the camera target); other occupants only when the
 * observer perceives them.
 */
class MapPanel(
    override val bounds: IntRect,
    private val gameWorld: GameWorld,
    private val fogFor: (Zone) -> Grid<Boolean>,
    // Whose eyes to draw (ADR-0015): the observer whose `Perceived` is rendered and on whom the
    // camera centres. Defaults to the player; point it at a companion or charmed monster to spectate.
    private val observer: (GameWorld) -> Entity? = { it.ecs.entitiesWith<Player, Position>().firstOrNull() },
    // How a remembered (previously-seen, not-currently-perceived) terrain cell is drawn. Defaults to
    // the engine's dim-to-dark-blue look; a game can dim differently via [dimmedRememberedRenderer]
    // or recolour remembered terrain arbitrarily (different glyph/hue), or return null to leave a
    // remembered tile undrawn. Currently-perceived (lit) cells are unaffected by this seam.
    private val rememberedRenderer: (Tile) -> RenderedCell? = DEFAULT_REMEMBERED_RENDERER,
    // How a currently-perceived occupant is drawn: remap its glyph/foreground at render time, or
    // return null to suppress it. Defaults to drawing the occupant's own [Renderable] unchanged. The
    // background stays engine-computed (see [backgroundAt]) so a remapped occupant can't desync from
    // the terrain tint beneath it. (krogue-7tx will extend this to remembered/unperceived occupants.)
    private val occupantRenderer: (OccupantRender) -> RenderedGlyph? = DEFAULT_OCCUPANT_RENDERER,
    // Presentation-only extra draw pass after terrain/occupants, given this frame's [MapCamera] so
    // it shares the exact viewport the map just drew (krogue-wuq's event-animation queue is the
    // first consumer: it renders its current sequence, world position -> screen, through here).
    // No-op by default.
    private val decorate: (TileSurface, MapCamera) -> Unit = { _, _ -> },
) : Widget {
    override fun draw(surface: TileSurface) {
        val zone = gameWorld.currentZone
        val observer = observer(gameWorld) ?: return
        val focus = observer.get<Position>() ?: return
        // The observer's cached perception, but only if it is for the zone we're drawing (a stale
        // cross-zone cache reveals nothing); absent entirely -> nothing perceived.
        val perceived = observer.get<Perceived>()?.takeIf { it.zoneId == zone.zoneId } ?: Perceived()
        val fog = fogFor(zone)

        val camera = MapCamera.centeredOn(focus.x, focus.y, surface.width, surface.height, zone.width, zone.height)

        accumulateFog(perceived, fog)

        drawTerrain(surface, zone, fog, perceived, camera)
        drawOccupants(surface, zone, observer, perceived, camera)
        decorate(surface, camera)
    }

    /** Mark every currently-perceived cell as remembered, so it stays drawn (dimmed) once out of view. */
    private fun accumulateFog(
        perceived: Perceived,
        fog: Grid<Boolean>,
    ) {
        for (cell in perceived.cells) {
            if (cell.x in 0 until fog.width && cell.y in 0 until fog.height) fog[cell.x, cell.y] = true
        }
    }

    private fun drawTerrain(
        surface: TileSurface,
        zone: Zone,
        fog: Grid<Boolean>,
        perceived: Perceived,
        camera: MapCamera,
    ) {
        for (screenY in 0 until surface.height) {
            val zy = camera.zoneY(screenY)
            if (zy < 0 || zy >= zone.height) continue
            for (screenX in 0 until surface.width) {
                val zx = camera.zoneX(screenX)
                if (zx < 0 || zx >= zone.width) continue
                val cell = terrainCell(zone, zx, zy, perceived, fog) ?: continue // hidden -> leave black
                surface.put(screenX, screenY, TERRAIN_Z, cell.glyph, cell.fg, cell.bg)
            }
        }
    }

    private fun drawOccupants(
        surface: TileSurface,
        zone: Zone,
        observer: Entity,
        perceived: Perceived,
        camera: MapCamera,
    ) {
        for (entity in gameWorld.ecs.entitiesWith<Position, Renderable, ZoneMember>()) {
            if (entity.require<ZoneMember>().zoneId != zone.zoneId) continue
            // The observer is the camera target and always drawn; everyone else only when perceived.
            if (entity.id != observer.id && !perceived.sees(entity.id)) continue
            val pos = entity.require<Position>()
            val screenX = camera.screenX(pos.x)
            val screenY = camera.screenY(pos.y)
            if (!camera.containsScreen(screenX, screenY)) continue
            val renderable = entity.require<Renderable>()
            // Hand the occupant's look to the game seam; null -> don't draw. Background stays
            // engine-computed so a remapped glyph keeps the terrain tint beneath it.
            val context = OccupantRender(entity, renderable, zone.tiles[pos.x, pos.y], perceived = true)
            val rendered = occupantRenderer(context) ?: continue
            surface.put(
                screenX,
                screenY,
                renderable.layer.zIndex,
                rendered.glyph,
                rendered.fg,
                backgroundAt(zone, pos.x, pos.y, perceived),
            )
        }
    }

    /**
     * A drawn terrain cell: the glyph and colors written to the surface. Public because it is the
     * return type of the [rememberedRenderer] seam, so a game can build its own remembered-cell look.
     */
    class RenderedCell(
        val glyph: Char,
        val fg: Color,
        val bg: Color,
    )

    /**
     * How an occupant is drawn: the [glyph] and foreground [fg] written to the surface. Unlike
     * [RenderedCell] it carries no background — the engine computes an occupant's background from the
     * terrain beneath it ([backgroundAt]), so the [occupantRenderer] seam can only remap the glyph/fg.
     */
    class RenderedGlyph(
        val glyph: Char,
        val fg: Color,
    )

    /**
     * What the [occupantRenderer] seam is told about an occupant it may draw: the [entity], its
     * [renderable], the [tile] beneath it, and whether the observer currently [perceived] it (always
     * true today; krogue-7tx will also invoke the seam for remembered/unperceived occupants).
     */
    class OccupantRender(
        val entity: Entity,
        val renderable: Renderable,
        val tile: Tile,
        val perceived: Boolean,
    )

    /** The terrain cell at ([x], [y]), with lighting/dimming applied, or null if currently hidden. */
    private fun terrainCell(
        zone: Zone,
        x: Int,
        y: Int,
        perceived: Perceived,
        fog: Grid<Boolean>,
    ): RenderedCell? {
        val tile = zone.tiles[x, y]
        return when {
            perceived.sees(x, y) -> {
                val lightVal = zone.lightMap[x, y]
                if (lightVal != null) {
                    val tint = lightVal.normalizedColor * lightVal.intensity
                    RenderedCell(
                        tile.glyph,
                        (tile.color.toNormalizedRgb() * tint).toColor(),
                        (tile.backgroundColor.toNormalizedRgb() * tint).toColor(),
                    )
                } else {
                    RenderedCell(tile.glyph, tile.color, tile.backgroundColor)
                }
            }
            // Previously seen but not currently perceived: hand off to the pluggable remembered look.
            fog[x, y] -> rememberedRenderer(tile)
            else -> null
        }
    }

    /** Background color under an occupant at ([x], [y]), matching [terrainCell]'s perceived/unlit logic. */
    private fun backgroundAt(
        zone: Zone,
        x: Int,
        y: Int,
        perceived: Perceived,
    ): Color {
        if (!perceived.sees(x, y)) return Color.BLACK
        val tile = zone.tiles[x, y]
        val lightVal = zone.lightMap[x, y] ?: return tile.backgroundColor
        val tint = lightVal.normalizedColor * lightVal.intensity
        return (tile.backgroundColor.toNormalizedRgb() * tint).toColor()
    }

    companion object {
        private const val TERRAIN_Z = 0
        private const val PREVIOUSLY_VIEWED_DIM_FACTOR = 0.35
        private const val PREVIOUSLY_VIEWED_BLUE_BOOST = 0.10f

        /**
         * A [rememberedRenderer] that dims a remembered tile's foreground to a dark tint on
         * [background], the engine's default look. [dimFactor] scales the lit foreground (lower =
         * darker) and [blueBoost] adds a cool cast; a game that only wants remembered terrain
         * brighter/warmer can pass its own values without writing the tint math itself.
         */
        fun dimmedRememberedRenderer(
            dimFactor: Double = PREVIOUSLY_VIEWED_DIM_FACTOR,
            blueBoost: Float = PREVIOUSLY_VIEWED_BLUE_BOOST,
            background: Color = Color.BLACK,
        ): (Tile) -> RenderedCell =
            { tile ->
                val fg =
                    (tile.color.toNormalizedRgb() * dimFactor)
                        .toColor()
                        .also { it.b = (it.b + blueBoost).coerceAtMost(1f) }
                RenderedCell(tile.glyph, fg, background)
            }

        /** The engine default remembered look: [dimmedRememberedRenderer] with the built-in values. */
        val DEFAULT_REMEMBERED_RENDERER: (Tile) -> RenderedCell? = dimmedRememberedRenderer()

        /** The engine default occupant look: draw the occupant's own [Renderable] glyph/color unchanged. */
        val DEFAULT_OCCUPANT_RENDERER: (OccupantRender) -> RenderedGlyph? =
            { RenderedGlyph(it.renderable.glyph, it.renderable.color.toColor()) }
    }
}
