package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.max
import kotlin.math.min

/**
 * The map as a [Widget] (ADR-0011): draws [GameWorld]'s current zone — terrain (with lighting
 * tints + previously-seen dimming) and the occupying entities — into its [bounds] on the supplied
 * [TileSurface]. Replaces the old window-blitting `KotileZoneRenderer`: instead of building a
 * zone-sized tilemap and viewporting the whole window, it samples a player-centred viewport sized
 * to its own pane and writes visible cells directly, so it composes with other panels in the one
 * UI render pass.
 *
 * Because it reads `gameWorld.currentZone` and the player position every frame, a zone transition
 * needs no renderer rebuild. Fog-of-war memory is fetched per zone via [fogFor] (krogue-ro8) and
 * accumulated in place.
 *
 * Layers (local z): terrain 0, then occupants by [Renderable.layer]'s `zIndex` (creature 1,
 * player 2). The player is always drawn (camera target); other occupants only when their cell is
 * currently visible.
 */
class MapPanel(
    override val bounds: IntRect,
    private val gameWorld: GameWorld,
    private val fogFor: (Zone) -> Grid<Boolean>,
    var losCalculator: LineOfSightCalculator,
    private val maximumVisibilityDistance: Double = DEFAULT_MAX_VISIBILITY,
) : Widget {
    override fun draw(surface: TileSurface) {
        val zone = gameWorld.currentZone
        val focus = gameWorld.ecs.entitiesWith<Player, Position>().firstOrNull()?.require<Position>() ?: return
        val fog = fogFor(zone)

        val originX = max(0, min(focus.x - surface.width / 2, zone.width - surface.width))
        val originY = max(0, min(focus.y - surface.height / 2, zone.height - surface.height))

        val visible = computeVisibility(zone, focus.x, focus.y)
        visible.forEachCoordinate { if (visible[it]) fog[it] = true }

        drawTerrain(surface, zone, fog, visible, originX, originY)
        drawOccupants(surface, zone, visible, originX, originY)
    }

    private fun drawTerrain(
        surface: TileSurface,
        zone: Zone,
        fog: Grid<Boolean>,
        visible: Grid<Boolean>,
        originX: Int,
        originY: Int,
    ) {
        for (screenY in 0 until surface.height) {
            val zy = originY + screenY
            if (zy < 0 || zy >= zone.height) continue
            for (screenX in 0 until surface.width) {
                val zx = originX + screenX
                if (zx < 0 || zx >= zone.width) continue
                val cell = terrainCell(zone, zx, zy, visible, fog) ?: continue // hidden -> leave black
                surface.put(screenX, screenY, TERRAIN_Z, cell.glyph, cell.fg, cell.bg)
            }
        }
    }

    private fun drawOccupants(
        surface: TileSurface,
        zone: Zone,
        visible: Grid<Boolean>,
        originX: Int,
        originY: Int,
    ) {
        for (entity in gameWorld.ecs.entitiesWith<Position, Renderable, ZoneMember>()) {
            if (entity.require<ZoneMember>().zoneId != zone.zoneId) continue
            val pos = entity.require<Position>()
            if (!entity.has<Player>() && !visible[pos.x, pos.y]) continue
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
                backgroundAt(zone, pos.x, pos.y, visible),
            )
        }
    }

    /** LOS from the focus, gated on lighting: a cell is visible only where the light map is non-null. */
    private fun computeVisibility(
        zone: Zone,
        focusX: Int,
        focusY: Int,
    ): Grid<Boolean> {
        val los =
            losCalculator.calculateLineOfSight(
                Vector2Int(focusX, focusY),
                zone.tiles,
                maximumVisibilityDistance,
            )
        los.forEachCoordinate { coord ->
            if (los[coord] && zone.lightMap[coord] == null) los[coord] = false
        }
        return los
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
        visible: Grid<Boolean>,
        fog: Grid<Boolean>,
    ): Rendered? {
        val tile = zone.tiles[x, y]
        return when {
            visible[x, y] -> {
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
                // Previously seen but not currently lit: dim to a dark-blue tint.
                val dimFg =
                    (tile.color.toNormalizedRgb() * PREVIOUSLY_VIEWED_DIM_FACTOR)
                        .toColor()
                        .also { it.b = (it.b + PREVIOUSLY_VIEWED_BLUE_BOOST).coerceAtMost(1f) }
                Rendered(tile.glyph, dimFg, Color.BLACK)
            }
            else -> null
        }
    }

    /** Background color under an occupant at ([x], [y]), matching [terrainCell]'s lit/unlit logic. */
    private fun backgroundAt(
        zone: Zone,
        x: Int,
        y: Int,
        visible: Grid<Boolean>,
    ): Color {
        if (!visible[x, y]) return Color.BLACK
        val tile = zone.tiles[x, y]
        val lightVal = zone.lightMap[x, y] ?: return tile.backgroundColor
        val tint = lightVal.normalizedColor * lightVal.intensity
        return (tile.backgroundColor.toNormalizedRgb() * tint).toColor()
    }

    companion object {
        private const val TERRAIN_Z = 0
        private const val DEFAULT_MAX_VISIBILITY = 30.0
        private const val PREVIOUSLY_VIEWED_DIM_FACTOR = 0.25
        private const val PREVIOUSLY_VIEWED_BLUE_BOOST = 0.10f
    }
}
