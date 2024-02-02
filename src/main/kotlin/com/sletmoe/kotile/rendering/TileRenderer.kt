package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector3Int
import javafx.scene.image.Image
import java.util.SortedMap

abstract class TileRenderer(protected val canvas: KotileCanvas) {
    val windowWidth: Int
        get() = canvas.width

    val windowHeight: Int
        get() = canvas.height

    private val layers: SortedMap<Int, Grid<StaticTile?>> = sortedMapOf(compareBy<Int> { it }.reversed())
    private val renderedTiles: Grid<StaticTile?> = Grid(windowWidth, windowHeight, null)

    suspend fun drawTile(position: Vector3Int, staticTile: StaticTile) {
        drawTile(position.x, position.y, position.z, staticTile)
    }

    suspend fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) {
        if (z !in layers) {
            layers[z] = Grid(windowWidth, windowHeight, null)
        }

        layers[z]!![x, y] = staticTile

        drawTileToCanvas(x, y)
    }

    suspend fun clearTile(position: Vector3Int) {
        clearTile(position.x, position.y, position.z)
    }

    suspend fun clearTile(x: Int, y: Int, z: Int) {
        if (z !in layers) {
            throw IndexOutOfBoundsException("Layer $z does not exist")
        } else {
            layers[z]!![x, y] = null
            drawTileToCanvas(x, y)
        }
    }

    private suspend fun drawTileToCanvas(x: Int, y: Int) {
        val topTile = getTopTile(x, y)

        if (topTile != null) {
            val tileImage = renderTile(x, y, topTile)
            canvas.drawTile(x, y, tileImage)
        } else {
            canvas.clearTile(x, y)
        }

        renderedTiles[x, y] = topTile
    }

    private fun getTopTile(x: Int, y: Int): StaticTile? {
        for (zLevel in layers.keys) {
            if (layers[zLevel]!![x, y] != null) {
                return layers[zLevel]!![x, y]
            }
        }

        return null
    }

    fun clear() {
        layers.clear()
        renderedTiles.clear()
        canvas.clear()
    }

    protected abstract suspend fun renderTile(x: Int, y: Int, staticTile: StaticTile): Image
}
