package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * Renders a [LayeredTilemap] of [StaticTile]s to a [KotileCanvas] each frame.
 *
 * Tiles are mutated with [drawTile]/[clearTile] and drawn by [render], which
 * redraws the whole grid (top-most tile per cell) every frame. Subclasses
 * implement [regionFor] to map a tile to the texture region representing it;
 * the tile's tint is applied at draw time.
 *
 * Call [onResize] from the application's resize callback so the internal
 * tilemap is rebuilt to match the new canvas dimensions. Tiles outside the new
 * bounds are dropped; tiles that still fit are preserved.
 *
 * To render a windowed slice of a larger logical tile space, use
 * [render(source, viewport)][render] with a consumer-owned [LayeredTilemap] and
 * a [TileViewport] describing the top-left origin. Logical cells outside the
 * source bounds are treated as empty (nothing is drawn for those screen cells).
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

    private var tilemap = LayeredTilemap(windowWidth, windowHeight)

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
    fun drawTile(position: Vector3Int, staticTile: StaticTile) = tilemap.addTile(position, staticTile)

    /** Places [staticTile] at column [x], row [y] on z-layer [z]. */
    fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) = tilemap.addTile(x, y, z, staticTile)

    /** Removes the tile at [position] (x, y, z-layer). */
    fun clearTile(position: Vector3Int) = tilemap.removeTile(position)

    /** Removes the tile at column [x], row [y] on z-layer [z]. */
    fun clearTile(x: Int, y: Int, z: Int) = tilemap.removeTile(x, y, z)

    /**
     * Draws the top-most tile of every cell to the canvas for this frame using
     * the renderer's internal tilemap.
     */
    fun render() {
        canvas.begin()
        renderGrid(tilemap, TileViewport())
        canvas.end()
    }

    /**
     * Draws a windowed slice of [source] to the canvas for this frame.
     *
     * For each screen cell `(screenX, screenY)` the logical cell sampled is
     * `(viewport.originX + screenX, viewport.originY + screenY)`. Logical cells
     * that fall outside [source]'s bounds are skipped silently — no tile is
     * drawn for those screen positions (they remain at the clear color).
     *
     * The viewport origin stays fixed across [onResize] calls; a resize changes
     * the visible cell count but does not move the origin, so the world does not
     * appear to scroll when the window grows or shrinks.
     *
     * @param source the logical tile space to sample from; may be larger than the visible window
     * @param viewport the top-left corner of the visible region in [source] tile coordinates;
     *   defaults to `(0, 0)` which reproduces the same behavior as [render]
     */
    fun render(source: LayeredTilemap, viewport: TileViewport = TileViewport()) {
        canvas.begin()
        renderGrid(source, viewport)
        canvas.end()
    }

    private fun renderGrid(source: LayeredTilemap, viewport: TileViewport) {
        for (screenY in 0 until windowHeight) {
            val logicalY = viewport.originY + screenY
            if (logicalY < 0 || logicalY >= source.height) continue
            for (screenX in 0 until windowWidth) {
                val logicalX = viewport.originX + screenX
                if (logicalX < 0 || logicalX >= source.width) continue
                val tile = source.topTileAt(logicalX, logicalY) ?: continue
                canvas.drawTile(screenX, screenY, regionFor(tile), tile.tint)
            }
        }
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
