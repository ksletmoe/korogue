package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.tintedByLight
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.utilities.distance
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Vector2Int

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
    // Wall-clock time for [LightEmitter.flicker] (krogue-ncl, ADR-0023's presentation axis) — the
    // game's running elapsedMs, e.g. `{ animationClockMs }` updated once per drawFrame. Defaults to
    // a constant 0, which is harmless: flicker only ever engages for an emitter that opts in via
    // a non-null [LightEmitter.flicker], so a game that never sets one is unaffected either way.
    private val elapsedMsProvider: () -> Long = { 0L },
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

        val flickerSources = flickerSourcesIn(zone)
        val elapsedMs = elapsedMsProvider()

        drawTerrain(surface, zone, fog, perceived, camera, flickerSources, elapsedMs)
        drawOccupants(surface, zone, observer, perceived, camera, flickerSources, elapsedMs)
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

    /**
     * Every [LightEmitter] with [LightEmitter.flicker] set, in [zone] — usually a small handful.
     * A single pass building straight into the result, not filter{}.map{} (which would look up
     * each entity's [LightEmitter] twice — once to test [LightEmitter.flicker], once to return it
     * — and allocate an intermediate filtered sequence before the final list).
     */
    private fun flickerSourcesIn(zone: Zone): List<Pair<Vector2Int, LightEmitter>> {
        val sources = mutableListOf<Pair<Vector2Int, LightEmitter>>()
        for (entity in gameWorld.ecs.entitiesWith<LightEmitter, Position, ZoneMember>()) {
            if (entity.require<ZoneMember>().zoneId != zone.zoneId) continue
            val emitter = entity.require<LightEmitter>()
            if (emitter.flicker == null) continue
            sources.add(entity.require<Position>().point to emitter)
        }
        return sources
    }

    /**
     * The wall-clock flicker multiplier at world cell ([x], [y]) — `1.0` (no change) when
     * [flickerSources] is empty or none reach this cell. Approximates "independent per source" by
     * picking the *nearest* flickering emitter that actually lights this cell (within its own
     * [LightEmitter.radius]) rather than recomputing the full per-emitter blend every frame (the
     * cost `Zone.lightMap`'s own tick-computed blend is there specifically to avoid) — a cell only
     * ever lit by one source in practice is unaffected by the approximation; deep overlap regions
     * flicker with whichever source is closest rather than a true blend of both phases.
     */
    private fun flickerFactorAt(
        x: Int,
        y: Int,
        flickerSources: List<Pair<Vector2Int, LightEmitter>>,
        elapsedMs: Long,
    ): Double {
        if (flickerSources.isEmpty()) return 1.0
        val coord = Vector2Int(x, y)
        // Manual loop, not filter{}.minByOrNull{} -- this runs once per visible cell and once per
        // visible occupant, every frame, so an intermediate List per call here is real per-frame
        // GC churn for a linear scan that doesn't need one (krogue-ojn).
        var nearest: LightEmitter? = null
        var nearestDist = Double.MAX_VALUE
        for ((origin, emitter) in flickerSources) {
            val dist = origin.distance(coord)
            if (dist <= emitter.radius && dist < nearestDist) {
                nearest = emitter
                nearestDist = dist
            }
        }
        return nearest?.flicker?.factorAt(elapsedMs) ?: 1.0
    }

    private fun drawTerrain(
        surface: TileSurface,
        zone: Zone,
        fog: Grid<Boolean>,
        perceived: Perceived,
        camera: MapCamera,
        flickerSources: List<Pair<Vector2Int, LightEmitter>>,
        elapsedMs: Long,
    ) {
        for (screenY in 0 until surface.height) {
            val zy = camera.zoneY(screenY)
            if (zy < 0 || zy >= zone.height) continue
            for (screenX in 0 until surface.width) {
                val zx = camera.zoneX(screenX)
                if (zx < 0 || zx >= zone.width) continue
                val flicker = flickerFactorAt(zx, zy, flickerSources, elapsedMs)
                val cell = terrainCell(zone, zx, zy, perceived, fog, flicker) ?: continue // hidden -> leave black
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
        flickerSources: List<Pair<Vector2Int, LightEmitter>>,
        elapsedMs: Long,
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
            val flicker = flickerFactorAt(pos.x, pos.y, flickerSources, elapsedMs)
            surface.put(
                screenX,
                screenY,
                renderable.layer.zIndex,
                rendered.glyph,
                rendered.fg,
                backgroundAt(zone, pos.x, pos.y, perceived, flicker),
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

    /**
     * The terrain cell at ([x], [y]), with lighting/dimming applied, or null if currently hidden.
     * [flicker] (krogue-ncl) scales the lit intensity, `1.0` reproducing the pre-flicker behavior.
     */
    private fun terrainCell(
        zone: Zone,
        x: Int,
        y: Int,
        perceived: Perceived,
        fog: Grid<Boolean>,
        flicker: Double,
    ): RenderedCell? {
        val tile = zone.tiles[x, y]
        return when {
            perceived.sees(x, y) -> {
                val lightVal = zone.lightMap[x, y]
                if (lightVal != null) {
                    val intensity = (lightVal.intensity * flicker).coerceIn(0.0, 1.0)
                    RenderedCell(
                        tile.glyph,
                        tile.color.tintedByLight(lightVal.normalizedColor, intensity),
                        tile.backgroundColor.tintedByLight(lightVal.normalizedColor, intensity),
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

    /**
     * Background color under an occupant at ([x], [y]), matching [terrainCell]'s perceived/unlit/
     * flicker logic — the occupant sits on the same lit terrain, so its background tint must track
     * the same wall-clock flicker or the floor beneath it would visibly desync from the floor
     * around it.
     */
    private fun backgroundAt(
        zone: Zone,
        x: Int,
        y: Int,
        perceived: Perceived,
        flicker: Double,
    ): Color {
        if (!perceived.sees(x, y)) return Color.BLACK
        val tile = zone.tiles[x, y]
        val lightVal = zone.lightMap[x, y] ?: return tile.backgroundColor
        val intensity = (lightVal.intensity * flicker).coerceIn(0.0, 1.0)
        return tile.backgroundColor.tintedByLight(lightVal.normalizedColor, intensity)
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
