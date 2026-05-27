package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.SpriteTileEntry
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.Tile
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * Renders a [LayeredTilemap] of [SpriteTileEntry] tiles to a [KotileCanvas]
 * each frame.
 *
 * Two concrete tile types are supported:
 * - [StaticTile] — coordinate-based; [regionFor] is called with the tile so
 *   subclasses can resolve the region from a sheet or other source.
 * - [Tile] (e.g. [com.sletmoe.kotile.tiles.AnimatedSpriteTile]) — owns its
 *   frames; [regionFor] is called with the elapsed wall-clock time so the tile
 *   can return the current animation frame.
 *
 * Tiles are mutated with [drawTile]/[clearTile] and drawn by [render], which
 * redraws the whole grid (top-most tile per cell) every frame. Subclasses
 * implement [regionFor] to map a [StaticTile] to the texture region
 * representing it; [Tile] instances resolve their own regions.
 *
 * Call [onResize] from the application's resize callback so the internal
 * tilemap is rebuilt to match the new canvas dimensions. Tiles outside the new
 * bounds are dropped; tiles that still fit are preserved.
 *
 * @param canvas the canvas tiles are drawn to
 */
public abstract class TileRenderer(protected val canvas: KotileCanvas) {
    /**
     * Current grid width in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    public val windowWidth: Int get() = canvas.width

    /**
     * Current grid height in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    public val windowHeight: Int get() = canvas.height

    private var tilemap = LayeredTilemap<SpriteTileEntry>(windowWidth, windowHeight)

    /**
     * Rebuilds the internal tilemap to fit the new pixel dimensions. Tiles
     * outside the new bounds are dropped; those still within bounds are
     * preserved. Call this from the application's resize callback.
     */
    public fun onResize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        tilemap = LayeredTilemap(windowWidth, windowHeight)
    }

    /** Places [staticTile] at [position] (x, y, z-layer). */
    public fun drawTile(position: Vector3Int, staticTile: StaticTile): Unit =
        tilemap.addTile(position, staticTile)

    /** Places [staticTile] at column [x], row [y] on z-layer [z]. */
    public fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile): Unit =
        tilemap.addTile(x, y, z, staticTile)

    /**
     * Places an animated [tile] at [position] (x, y, z-layer).
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    public fun drawTile(position: Vector3Int, tile: Tile): Unit = tilemap.addTile(position, tile)

    /**
     * Places an animated [tile] at column [x], row [y] on z-layer [z].
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    public fun drawTile(x: Int, y: Int, z: Int, tile: Tile): Unit = tilemap.addTile(x, y, z, tile)

    /** Removes the tile at [position] (x, y, z-layer). */
    public fun clearTile(position: Vector3Int): Unit = tilemap.removeTile(position)

    /** Removes the tile at column [x], row [y] on z-layer [z]. */
    public fun clearTile(x: Int, y: Int, z: Int): Unit = tilemap.removeTile(x, y, z)

    /**
     * Draws the top-most tile of every cell to the canvas for this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [Tile] (animated) entries.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   renderers that only use [StaticTile].
     */
    public fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        for (y in 0 until windowHeight) {
            for (x in 0 until windowWidth) {
                when (val entry = tilemap.topTileAt(x, y) ?: continue) {
                    is StaticTile -> canvas.drawTile(x, y, regionFor(entry), entry.tint)
                    is Tile -> canvas.drawTile(x, y, entry.regionFor(elapsedMs), entry.tint)
                }
            }
        }
        canvas.end()
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
