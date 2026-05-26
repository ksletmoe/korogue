package com.sletmoe.kotile.utilities

import com.sletmoe.kotile.tiles.StaticTile
import java.util.SortedMap

class LayeredTilemap(val width: Int, val height: Int) {
    private val layers: SortedMap<Int, Grid<StaticTile?>> = sortedMapOf(compareByDescending { it })

    fun addTile(position: Vector3Int, staticTile: StaticTile) = addTile(position.x, position.y, position.z, staticTile)

    fun addTile(x: Int, y: Int, z: Int, staticTile: StaticTile) {
        if (z !in layers) {
            layers[z] = Grid(width, height, null)
        }

        layers[z]!![x, y] = staticTile
    }

    fun removeTile(position: Vector3Int) = removeTile(position.x, position.y, position.z)

    fun removeTile(x: Int, y: Int, z: Int) {
        if (z !in layers) {
            throw IndexOutOfBoundsException("No layer $z")
        }

        layers[z]!![x, y] = null
    }

    fun moveTile(from: Vector3Int, to: Vector3Int) = moveTile(from.x, from.y, from.z, to.x, to.y, to.z)

    fun moveTile(fromX: Int, fromY: Int, fromZ: Int, toX: Int, toY: Int, toZ: Int) {
        if (fromZ !in layers) {
            throw IndexOutOfBoundsException("No layer $fromZ")
        }
        if (toZ !in layers) {
            throw IndexOutOfBoundsException("No layer $toZ")
        }

        val tile = layers[fromZ]!![fromX, fromY] ?: return
        layers[fromZ]!![fromX, fromY] = null
        layers[toZ]!![toX, toY] = tile
    }

    fun topTileAt(position: Vector2Int): StaticTile? = topTileAt(position.x, position.y)

    fun topTileAt(x: Int, y: Int): StaticTile? {
        layers.forEach { (_, tileGrid) ->
            if (tileGrid[x, y] != null) {
                return tileGrid[x, y]
            }
        }

        return null
    }
}
