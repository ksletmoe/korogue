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
 * To render a windowed slice of a larger logical tile space, use the
 * [render(source, viewport)][render] overload with a consumer-owned
 * [LayeredTilemap] and a [TileViewport] describing the top-left origin.
 * Logical cells outside the source bounds are skipped silently.
 *
 * @param canvas the canvas tiles are drawn to
 */
abstract class TileRenderer(protected val canvas: KotileCanvas) {
    /**
     * Current grid width in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    val windowWidth: Int get() = canvas.width

    /**
     * Current grid height in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    val windowHeight: Int get() = canvas.height

    private var tilemap = LayeredTilemap<SpriteTileEntry>(windowWidth, windowHeight)

    /**
     * Rebuilds the internal tilemap to fit the new pixel dimensions. Tiles
     * outside the new bounds are dropped; those still within bounds are
     * preserved. Call this from the application's resize callback.
     */
    fun onResize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        tilemap = LayeredTilemap(windowWidth, windowHeight)
    }

    /** Places [staticTile] at [position] (x, y, z-layer). */
    fun drawTile(position: Vector3Int, staticTile: StaticTile) = tilemap.setCell(position, staticTile)

    /** Places [staticTile] at column [x], row [y] on z-layer [z]. */
    fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) = tilemap.setCell(x, y, z, staticTile)

    /**
     * Places an animated [tile] at [position] (x, y, z-layer).
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    fun drawTile(position: Vector3Int, tile: Tile) = tilemap.setCell(position, tile)

    /**
     * Places an animated [tile] at column [x], row [y] on z-layer [z].
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    fun drawTile(x: Int, y: Int, z: Int, tile: Tile) = tilemap.setCell(x, y, z, tile)

    /** Removes the tile at [position] (x, y, z-layer). */
    fun clearTile(position: Vector3Int) = tilemap.removeCell(position)

    /** Removes the tile at column [x], row [y] on z-layer [z]. */
    fun clearTile(x: Int, y: Int, z: Int) = tilemap.removeCell(x, y, z)

    /**
     * Draws the top-most tile of every cell of the internal tilemap to the
     * canvas for this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [Tile] (animated) entries.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   renderers that only use [StaticTile].
     */
    fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        renderGrid(tilemap, TileViewport(), elapsedMs)
        canvas.end()
    }

    /**
     * Draws a windowed slice of [source] to the canvas for this frame.
     *
     * For each screen cell `(screenX, screenY)` the logical cell sampled is
     * `(viewport.originX + screenX, viewport.originY + screenY)`. Logical cells
     * that fall outside [source]'s bounds are skipped silently — no tile is
     * drawn for those screen positions.
     *
     * The viewport origin stays fixed across [onResize] calls; a resize changes
     * the visible cell count but does not move the origin, so the world does
     * not appear to scroll when the window grows or shrinks.
     *
     * @param source the logical tile space to sample from; may be larger than the visible window
     * @param viewport the top-left corner of the visible region in [source] tile coordinates;
     *   defaults to `(0, 0)` which samples from the source's origin
     * @param elapsedMs wall-clock time for resolving any animated [Tile] entries
     */
    fun render(
        source: LayeredTilemap<SpriteTileEntry>,
        viewport: TileViewport = TileViewport(),
        elapsedMs: Long = 0L,
    ) {
        canvas.begin()
        renderGrid(source, viewport, elapsedMs)
        canvas.end()
    }

    private fun renderGrid(
        source: LayeredTilemap<SpriteTileEntry>,
        viewport: TileViewport,
        elapsedMs: Long,
    ) {
        for (screenY in 0 until windowHeight) {
            val logicalY = viewport.originY + screenY
            if (logicalY < 0 || logicalY >= source.height) continue
            for (screenX in 0 until windowWidth) {
                val logicalX = viewport.originX + screenX
                if (logicalX < 0 || logicalX >= source.width) continue
                when (val entry = source.topCellAt(logicalX, logicalY) ?: continue) {
                    is StaticTile -> canvas.drawTile(screenX, screenY, regionFor(entry), entry.tint)
                    is Tile -> canvas.drawTile(screenX, screenY, entry.regionFor(elapsedMs), entry.tint)
                }
            }
        }
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
