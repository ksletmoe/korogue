package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * Renders a [LayeredTilemap] of [StaticTile]s to a [KotileCanvas] each frame.
 *
 * Subclasses map a tile to the texture region that represents it; the tile's
 * tint is applied at draw time.
 */
abstract class TileRenderer(protected val canvas: KotileCanvas) {
    val windowWidth: Int = canvas.width
    val windowHeight: Int = canvas.height

    private val tilemap = LayeredTilemap(windowWidth, windowHeight)

    fun drawTile(position: Vector3Int, staticTile: StaticTile) = tilemap.addTile(position, staticTile)

    fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) = tilemap.addTile(x, y, z, staticTile)

    fun clearTile(position: Vector3Int) = tilemap.removeTile(position)

    fun clearTile(x: Int, y: Int, z: Int) = tilemap.removeTile(x, y, z)

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

    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
