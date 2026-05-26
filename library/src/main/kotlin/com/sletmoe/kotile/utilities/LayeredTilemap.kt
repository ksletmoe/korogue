package com.sletmoe.kotile.utilities

import com.sletmoe.kotile.tiles.StaticTile
import java.util.SortedMap

/**
 * Stores [StaticTile]s on stacked z-layers, each a [width] x [height] grid.
 * Layers are created on demand and queried top-down by [topTileAt] (higher z
 * wins).
 *
 * @property width grid width in tiles
 * @property height grid height in tiles
 */
class LayeredTilemap(val width: Int, val height: Int) {
    private val layers: SortedMap<Int, Grid<StaticTile?>> = sortedMapOf(compareByDescending { it })

    /** Places [staticTile] at [position] (x, y, z), creating layer z if needed. */
    fun addTile(position: Vector3Int, staticTile: StaticTile) = addTile(position.x, position.y, position.z, staticTile)

    /** Places [staticTile] at column [x], row [y] on layer [z], creating it if needed. */
    fun addTile(x: Int, y: Int, z: Int, staticTile: StaticTile) {
        if (z !in layers) {
            layers[z] = Grid(width, height, null)
        }

        layers[z]!![x, y] = staticTile
    }

    /**
     * Removes the tile at [position] (x, y, z).
     *
     * @throws IndexOutOfBoundsException if that layer does not exist
     */
    fun removeTile(position: Vector3Int) = removeTile(position.x, position.y, position.z)

    /**
     * Removes the tile at column [x], row [y] on layer [z].
     *
     * @throws IndexOutOfBoundsException if layer [z] does not exist
     */
    fun removeTile(x: Int, y: Int, z: Int) {
        if (z !in layers) {
            throw IndexOutOfBoundsException("No layer $z")
        }

        layers[z]!![x, y] = null
    }

    /**
     * Moves the tile at [from] to [to]. Does nothing if the source cell is empty.
     *
     * @throws IndexOutOfBoundsException if either layer does not exist
     */
    fun moveTile(from: Vector3Int, to: Vector3Int) = moveTile(from.x, from.y, from.z, to.x, to.y, to.z)

    /**
     * Moves the tile at ([fromX], [fromY]) on layer [fromZ] to ([toX], [toY])
     * on layer [toZ]. Does nothing if the source cell is empty.
     *
     * @throws IndexOutOfBoundsException if either layer does not exist
     */
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

    /** Returns the top-most (highest z) tile at [position], or `null` if empty. */
    fun topTileAt(position: Vector2Int): StaticTile? = topTileAt(position.x, position.y)

    /** Returns the top-most (highest z) tile at column [x], row [y], or `null` if empty. */
    fun topTileAt(x: Int, y: Int): StaticTile? {
        layers.forEach { (_, tileGrid) ->
            if (tileGrid[x, y] != null) {
                return tileGrid[x, y]
            }
        }

        return null
    }
}
