package com.sletmoe.kotile.utilities

import java.util.SortedMap

/**
 * Stores tiles of type [T] on stacked z-layers, each a [width] x [height]
 * grid. Layers are created on demand and queried top-down by [topTileAt]
 * (higher z wins).
 *
 * All mutating operations ([addTile], [removeTile], [moveTile]) use a
 * consistent create-on-demand policy: if the target layer does not yet exist
 * it is created automatically. Calls on a missing *source* layer (e.g.
 * [removeTile] or the source of [moveTile]) are silent no-ops rather than
 * errors, because the observable state is already what the caller wanted.
 *
 * @param T the tile type stored in this map
 * @property width grid width in tiles
 * @property height grid height in tiles
 */
public class LayeredTilemap<T : Any>(public val width: Int, public val height: Int) {
    private val layers: SortedMap<Int, Grid<T?>> = sortedMapOf(compareByDescending { it })

    /** Places [tile] at [position] (x, y, z), creating layer z if needed. */
    public fun addTile(position: Vector3Int, tile: T) = addTile(position.x, position.y, position.z, tile)

    /** Places [tile] at column [x], row [y] on layer [z], creating it if needed. */
    public fun addTile(x: Int, y: Int, z: Int, tile: T) {
        layers.getOrPut(z) { Grid(width, height, null) }[x, y] = tile
    }

    /**
     * Removes the tile at [position] (x, y, z). No-op if layer z does not
     * exist.
     */
    public fun removeTile(position: Vector3Int) = removeTile(position.x, position.y, position.z)

    /**
     * Removes the tile at column [x], row [y] on layer [z]. No-op if layer
     * [z] does not exist.
     */
    public fun removeTile(x: Int, y: Int, z: Int) {
        layers[z]?.set(x, y, null)
    }

    /**
     * Moves the tile at [from] to [to]. No-op if the source layer does not
     * exist or the source cell is empty. The destination layer is created on
     * demand.
     */
    public fun moveTile(from: Vector3Int, to: Vector3Int) = moveTile(from.x, from.y, from.z, to.x, to.y, to.z)

    /**
     * Moves the tile at ([fromX], [fromY]) on layer [fromZ] to ([toX], [toY])
     * on layer [toZ]. No-op if layer [fromZ] does not exist or the source cell
     * is empty. Layer [toZ] is created on demand.
     */
    public fun moveTile(fromX: Int, fromY: Int, fromZ: Int, toX: Int, toY: Int, toZ: Int) {
        val tile = layers[fromZ]?.get(fromX, fromY) ?: return
        layers[fromZ]!![fromX, fromY] = null
        layers.getOrPut(toZ) { Grid(width, height, null) }[toX, toY] = tile
    }

    /** Returns the top-most (highest z) tile at [position], or `null` if empty. */
    public fun topTileAt(position: Vector2Int): T? = topTileAt(position.x, position.y)

    /** Returns the top-most (highest z) tile at column [x], row [y], or `null` if empty. */
    public fun topTileAt(x: Int, y: Int): T? {
        layers.forEach { (_, tileGrid) ->
            if (tileGrid[x, y] != null) {
                return tileGrid[x, y]
            }
        }

        return null
    }
}
