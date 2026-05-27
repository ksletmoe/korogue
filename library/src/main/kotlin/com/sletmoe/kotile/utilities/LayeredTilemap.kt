package com.sletmoe.kotile.utilities

import com.sletmoe.kotile.tiles.StaticTile
import java.util.SortedMap

/**
 * Stores [StaticTile]s on stacked z-layers, each a [width] x [height] grid.
 * Layers are created on demand and queried top-down by [topTileAt] (higher z
 * wins).
 *
 * All mutating operations ([addTile], [removeTile], [moveTile]) use a
 * consistent create-on-demand policy: if the target layer does not yet exist
 * it is created automatically. Calls on a missing *source* layer (e.g.
 * [removeTile] or the source of [moveTile]) are silent no-ops rather than
 * errors, because the observable state is already what the caller wanted.
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
        layers.getOrPut(z) { Grid(width, height, null) }[x, y] = staticTile
    }

    /**
     * Removes the tile at [position] (x, y, z). No-op if layer z does not
     * exist.
     */
    fun removeTile(position: Vector3Int) = removeTile(position.x, position.y, position.z)

    /**
     * Removes the tile at column [x], row [y] on layer [z]. No-op if layer
     * [z] does not exist.
     */
    fun removeTile(x: Int, y: Int, z: Int) {
        layers[z]?.set(x, y, null)
    }

    /**
     * Moves the tile at [from] to [to]. No-op if the source layer does not
     * exist or the source cell is empty. The destination layer is created on
     * demand.
     */
    fun moveTile(from: Vector3Int, to: Vector3Int) = moveTile(from.x, from.y, from.z, to.x, to.y, to.z)

    /**
     * Moves the tile at ([fromX], [fromY]) on layer [fromZ] to ([toX], [toY])
     * on layer [toZ]. No-op if layer [fromZ] does not exist or the source cell
     * is empty. Layer [toZ] is created on demand.
     */
    fun moveTile(fromX: Int, fromY: Int, fromZ: Int, toX: Int, toY: Int, toZ: Int) {
        val tile = layers[fromZ]?.get(fromX, fromY) ?: return
        layers[fromZ]!![fromX, fromY] = null
        layers.getOrPut(toZ) { Grid(width, height, null) }[toX, toY] = tile
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
