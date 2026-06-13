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
import com.sletmoe.korogue.world.Zone
import kotlin.math.max
import kotlin.math.min

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
) : Widget {
    override fun draw(surface: TileSurface) {
        val zone = gameWorld.currentZone
        val observer = observer(gameWorld) ?: return
        val focus = observer.get<Position>() ?: return
        // The observer's cached perception, but only if it is for the zone we're drawing (a stale
        // cross-zone cache reveals nothing); absent entirely -> nothing perceived.
        val perceived = observer.get<Perceived>()?.takeIf { it.zoneId == zone.zoneId } ?: Perceived()
        val fog = fogFor(zone)

        val originX = max(0, min(focus.x - surface.width / 2, zone.width - surface.width))
        val originY = max(0, min(focus.y - surface.height / 2, zone.height - surface.height))

        accumulateFog(perceived, fog)

        drawTerrain(surface, zone, fog, perceived, originX, originY)
        drawOccupants(surface, zone, observer, perceived, originX, originY)
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
        originX: Int,
        originY: Int,
    ) {
        for (screenY in 0 until surface.height) {
            val zy = originY + screenY
            if (zy < 0 || zy >= zone.height) continue
            for (screenX in 0 until surface.width) {
                val zx = originX + screenX
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
        originX: Int,
        originY: Int,
    ) {
        for (entity in gameWorld.ecs.entitiesWith<Position, Renderable, ZoneMember>()) {
            if (entity.require<ZoneMember>().zoneId != zone.zoneId) continue
            // The observer is the camera target and always drawn; everyone else only when perceived.
            if (entity.id != observer.id && !perceived.sees(entity.id)) continue
            val pos = entity.require<Position>()
            val screenX = pos.x - originX
            val screenY = pos.y - originY
            if (screenX < 0 || screenX >= surface.width || screenY < 0 || screenY >= surface.height) continue
            val renderable = entity.require<Renderable>()
            surface.put(
                screenX,
                screenY,
                renderable.layer.zIndex,
                renderable.glyph,
                renderable.color.toColor(),
                backgroundAt(zone, pos.x, pos.y, perceived),
            )
        }
    }

    private class Rendered(
        val glyph: Char,
        val fg: Color,
        val bg: Color,
    )

    /** The terrain cell at ([x], [y]), with lighting/dimming applied, or null if currently hidden. */
    private fun terrainCell(
        zone: Zone,
        x: Int,
        y: Int,
        perceived: Perceived,
        fog: Grid<Boolean>,
    ): Rendered? {
        val tile = zone.tiles[x, y]
        return when {
            perceived.sees(x, y) -> {
                val lightVal = zone.lightMap[x, y]
                if (lightVal != null) {
                    val tint = lightVal.normalizedColor * lightVal.intensity
                    Rendered(
                        tile.glyph,
                        (tile.color.toNormalizedRgb() * tint).toColor(),
                        (tile.backgroundColor.toNormalizedRgb() * tint).toColor(),
                    )
                } else {
                    Rendered(tile.glyph, tile.color, tile.backgroundColor)
                }
            }
            fog[x, y] -> {
                // Previously seen but not currently perceived: dim to a dark-blue tint.
                val dimFg =
                    (tile.color.toNormalizedRgb() * PREVIOUSLY_VIEWED_DIM_FACTOR)
                        .toColor()
                        .also { it.b = (it.b + PREVIOUSLY_VIEWED_BLUE_BOOST).coerceAtMost(1f) }
                Rendered(tile.glyph, dimFg, Color.BLACK)
            }
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
        private const val PREVIOUSLY_VIEWED_DIM_FACTOR = 0.25
        private const val PREVIOUSLY_VIEWED_BLUE_BOOST = 0.10f
    }
}
