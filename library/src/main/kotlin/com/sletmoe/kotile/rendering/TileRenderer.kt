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
 * the tile's tint is applied at draw time. The grid is sized from the canvas
 * when the renderer is constructed.
 *
 * @param canvas the canvas tiles are drawn to
 */
abstract class TileRenderer(protected val canvas: KotileCanvas) {
    /** Grid width in tiles, captured from the canvas at construction. */
    val windowWidth: Int = canvas.width

    /** Grid height in tiles, captured from the canvas at construction. */
    val windowHeight: Int = canvas.height

    private val tilemap = LayeredTilemap(windowWidth, windowHeight)

    /** Places [staticTile] at [position] (x, y, z-layer). */
    fun drawTile(position: Vector3Int, staticTile: StaticTile) = tilemap.addTile(position, staticTile)

    /** Places [staticTile] at column [x], row [y] on z-layer [z]. */
    fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) = tilemap.addTile(x, y, z, staticTile)

    /** Removes the tile at [position] (x, y, z-layer). */
    fun clearTile(position: Vector3Int) = tilemap.removeTile(position)

    /** Removes the tile at column [x], row [y] on z-layer [z]. */
    fun clearTile(x: Int, y: Int, z: Int) = tilemap.removeTile(x, y, z)

    /** Draws the top-most tile of every cell to the canvas for this frame. */
    fun render() {
        canvas.begin()
        for (y in 0 until windowHeight) {
            for (x in 0 until windowWidth) {
                val tile = tilemap.topTileAt(x, y) ?: continue
                canvas.drawTile(x, y, regionFor(tile), tile.tint)
            }
        }
        canvas.end()
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
