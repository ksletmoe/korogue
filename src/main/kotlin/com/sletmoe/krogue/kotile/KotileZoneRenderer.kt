package com.sletmoe.krogue.kotile

import com.sletmoe.kotile.display.ascii.AnimatableAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.TileViewport
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.Creature
import com.sletmoe.krogue.world.Zone
import java.awt.Point
import kotlin.math.max
import kotlin.math.min
import com.badlogic.gdx.graphics.Color as GdxColor

/** Z-layer indices for the shared [LayeredTilemap]. */
private const val LAYER_TILES = 0
private const val LAYER_CREATURES = 1
private const val LAYER_PLAYER = 2

/** Tile drawn for cells outside the zone bounds or hidden by FOV. */
private val EMPTY_TILE = AsciiTileDescriptor(' ', GdxColor.BLACK, GdxColor.BLACK)

/**
 * Renders a krogue [Zone] into an [AsciiTileWindow] using kotile's layered tilemap
 * and viewport API. Supports FOV (via [lineOfSightCalculator]), lighting tints from
 * [Zone.lightMap], and previously-viewed tile dimming.
 *
 * Layer assignments:
 * - z=0: terrain tiles
 * - z=1: non-player creatures
 * - z=2: player
 *
 * The camera centres on [focusPoint]; the viewport is clamped to zone bounds so the
 * player can never scroll the map beyond its edges.
 *
 * This class owns no GPU resources. It writes into a [LayeredTilemap] and calls
 * [AsciiTileWindow.render] — GPU ownership stays with the [AsciiTileWindow].
 */
internal class KotileZoneRenderer(
    private val zone: Zone,
    private val window: AsciiTileWindow,
    private var lineOfSightCalculator: LineOfSightCalculator,
    private val maximumVisibilityDistance: Double = 30.0,
) {
    private val logicalMap = LayeredTilemap<AnimatableAsciiTile>(zone.width, zone.height)

    /**
     * Visibility state persisted across frames for the "previously viewed" dimming effect.
     * Indexed [x + y*width].
     */
    private val previouslyVisible = Grid(zone.width, zone.height, false)

    /** The LOS calculator to use next frame. Thread-unsafe; toggle from the render thread. */
    var losCalculator: LineOfSightCalculator
        get() = lineOfSightCalculator
        set(value) {
            lineOfSightCalculator = value
        }

    /**
     * Rebuilds the [LayeredTilemap] from the zone's current state and blits it through
     * [window] using a player-centred [TileViewport].
     *
     * @param focusPoint tile coordinate the camera should centre on (typically the player position).
     * @param player the player [Creature]; rendered on [LAYER_PLAYER] on top of everything.
     * @param elapsedMs wall-clock milliseconds passed to [AsciiTileWindow.render] for animation.
     */
    fun render(
        focusPoint: Point,
        player: Creature,
        elapsedMs: Long = 0L,
    ) {
        val viewport = buildViewport(focusPoint)
        val visibilityGrid = computeVisibility(focusPoint)

        // Update previously-visible accumulator.
        visibilityGrid.forEachCoordinate { coord ->
            if (visibilityGrid[coord]) previouslyVisible[coord] = true
        }

        // Clear all layers before repopulating.
        logicalMap.clearAllLayers()

        // Layer 0: terrain.
        for (y in 0 until zone.height) {
            for (x in 0 until zone.width) {
                val tileDescriptor = buildTileDescriptor(x, y, visibilityGrid)
                logicalMap.setCell(x, y, LAYER_TILES, tileDescriptor)
            }
        }

        // Layer 1: non-player creatures.
        for (creature in zone.creatures) {
            if (creature === player) continue
            val cx = creature.position.x
            val cy = creature.position.y
            if (!visibilityGrid[cx, cy]) continue
            val tileBg = tileBackgroundAt(cx, cy, visibilityGrid)
            logicalMap.setCell(
                cx,
                cy,
                LAYER_CREATURES,
                AsciiTileDescriptor(creature.glyph, creature.color.toGdxColor(), tileBg),
            )
        }

        // Layer 2: player (always drawn at player position regardless of FOV).
        run {
            val px = player.position.x
            val py = player.position.y
            val tileBg = tileBackgroundAt(px, py, visibilityGrid)
            logicalMap.setCell(
                px,
                py,
                LAYER_PLAYER,
                AsciiTileDescriptor(player.glyph, player.color.toGdxColor(), tileBg),
            )
        }

        window.render(logicalMap, viewport, elapsedMs)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Computes a player-centred viewport clamped to zone bounds. */
    private fun buildViewport(focus: Point): TileViewport {
        val w = window.widthInTiles
        val h = window.heightInTiles
        val originX = max(0, min(focus.x - w / 2, zone.width - w))
        val originY = max(0, min(focus.y - h / 2, zone.height - h))
        return TileViewport(originX, originY)
    }

    /** Runs the LOS algorithm and optionally gates on the light map. */
    private fun computeVisibility(focus: Point): Grid<Boolean> {
        val los = lineOfSightCalculator.calculateLineOfSight(focus, zone.tiles, maximumVisibilityDistance)
        // Gate on lighting: a lit cell is only visible if the light map is non-null there.
        los.forEachCoordinate { coord ->
            if (los[coord] && zone.lightMap[coord] == null) {
                los[coord] = false
            }
        }
        return los
    }

    /**
     * Builds the terrain [AsciiTileDescriptor] for a single zone cell, applying
     * FOV darkness, previously-viewed dimming, and lighting tints.
     */
    private fun buildTileDescriptor(
        x: Int,
        y: Int,
        visible: Grid<Boolean>,
    ): AsciiTileDescriptor {
        val tile = zone.tiles[x, y]
        return when {
            visible[x, y] -> {
                // Visible: apply lighting tint.
                val lightVal = zone.lightMap[x, y]
                if (lightVal != null) {
                    val tintRgb = lightVal.normalizedColor * lightVal.intensity
                    val tintedFg = (tile.color.toNormalizedRgb() * tintRgb).toGdxColor()
                    val tintedBg = (tile.backgroundColor.toNormalizedRgb() * tintRgb).toGdxColor()
                    AsciiTileDescriptor(tile.glyph, tintedFg, tintedBg)
                } else {
                    AsciiTileDescriptor(tile.glyph, tile.color.toGdxColor(), tile.backgroundColor.toGdxColor())
                }
            }
            previouslyVisible[x, y] -> {
                // Previously seen but not currently lit: dim to a dark-blue tint.
                val dimFg = tile.color.toNormalizedRgb() * PREVIOUSLY_VIEWED_DIM_FACTOR
                AsciiTileDescriptor(
                    tile.glyph,
                    dimFg.toGdxColor().also { it.b = (it.b + PREVIOUSLY_VIEWED_BLUE_BOOST).coerceAtMost(1f) },
                    GdxColor.BLACK,
                )
            }
            else -> EMPTY_TILE
        }
    }

    /**
     * Returns the GDX background color for the tile at ([x], [y]) after applying
     * visibility and lighting, matching the logic in [buildTileDescriptor].
     */
    private fun tileBackgroundAt(
        x: Int,
        y: Int,
        visible: Grid<Boolean>,
    ): GdxColor {
        if (!visible[x, y]) return GdxColor.BLACK
        val tile = zone.tiles[x, y]
        val lightVal = zone.lightMap[x, y]
        return if (lightVal != null) {
            val tintRgb = lightVal.normalizedColor * lightVal.intensity
            (tile.backgroundColor.toNormalizedRgb() * tintRgb).toGdxColor()
        } else {
            tile.backgroundColor.toGdxColor()
        }
    }

    companion object {
        private const val PREVIOUSLY_VIEWED_DIM_FACTOR = 0.25
        private const val PREVIOUSLY_VIEWED_BLUE_BOOST = 0.10f
    }
}

// ---------------------------------------------------------------------------
// NormalizedRgb → GDX Color output (without going through AWT)
// ---------------------------------------------------------------------------

private fun com.sletmoe.krogue.algorithms.color.NormalizedRgb.toGdxColor(): GdxColor =
    GdxColor(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), 1f)
