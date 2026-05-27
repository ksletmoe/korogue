package com.sletmoe.kotile.utilities

import java.util.SortedMap

/**
 * Stores nullable cell values on stacked z-layers, each a [width] x [height]
 * [Grid]. Layers are created on demand and queried top-down by [topCellAt]
 * (higher z wins).
 *
 * This class is generic over the *non-null* cell type [T]; every grid slot
 * holds `T?`, where `null` means "empty". The type parameter lets both the
 * sprite-tile path ([com.sletmoe.kotile.rendering.TileRenderer] with
 * [com.sletmoe.kotile.tiles.StaticTile]) and the ASCII path
 * ([com.sletmoe.kotile.display.ascii.AsciiTileWindow] with
 * [com.sletmoe.kotile.display.ascii.AsciiTileDescriptor]) share the same
 * layering logic without duplication.
 *
 * **Create-on-demand / permissive policy** (mirrored from both paths):
 * - [setCell] creates the target layer if it does not yet exist.
 * - [removeCell] on a missing layer is a silent no-op.
 * - [moveCell] with a missing *source* layer (or an empty source cell) is a
 *   silent no-op; the destination layer is created on demand.
 * - [clearLayer] on a missing layer is a silent no-op.
 *
 * @param T non-null cell type stored in the tilemap
 * @property width grid width in cells
 * @property height grid height in cells
 */
class LayeredTilemap<T : Any>(val width: Int, val height: Int) {
    // Layers sorted descending so topCellAt iteration returns highest z first.
    private val layers: SortedMap<Int, Grid<T?>> = sortedMapOf(compareByDescending { it })

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /**
     * Places [cell] at [position] (x, y, z), creating layer z if needed.
     *
     * Equivalent to `setCell(position.x, position.y, position.z, cell)`.
     */
    fun setCell(position: Vector3Int, cell: T) = setCell(position.x, position.y, position.z, cell)

    /**
     * Places [cell] at column [x], row [y] on layer [z], creating the layer
     * if it does not yet exist.
     */
    fun setCell(x: Int, y: Int, z: Int, cell: T) {
        layers.getOrPut(z) { Grid(width, height, null) }[x, y] = cell
    }

    /**
     * Removes the cell at [position] (x, y, z). No-op if layer z does not
     * exist.
     *
     * Equivalent to `removeCell(position.x, position.y, position.z)`.
     */
    fun removeCell(position: Vector3Int) = removeCell(position.x, position.y, position.z)

    /**
     * Clears the cell at column [x], row [y] on layer [z] (sets it to `null`).
     * No-op if layer [z] does not exist.
     */
    fun removeCell(x: Int, y: Int, z: Int) {
        layers[z]?.set(x, y, null)
    }

    /**
     * Moves the cell at [from] to [to]. No-op if the source layer does not
     * exist or the source cell is empty. The destination layer is created on
     * demand.
     *
     * Equivalent to
     * `moveCell(from.x, from.y, from.z, to.x, to.y, to.z)`.
     */
    fun moveCell(from: Vector3Int, to: Vector3Int) =
        moveCell(from.x, from.y, from.z, to.x, to.y, to.z)

    /**
     * Moves the cell at ([fromX], [fromY]) on layer [fromZ] to ([toX], [toY])
     * on layer [toZ]. No-op if layer [fromZ] does not exist or the source cell
     * is empty. Layer [toZ] is created on demand.
     */
    fun moveCell(fromX: Int, fromY: Int, fromZ: Int, toX: Int, toY: Int, toZ: Int) {
        val cell = layers[fromZ]?.get(fromX, fromY) ?: return
        layers[fromZ]!![fromX, fromY] = null
        layers.getOrPut(toZ) { Grid(width, height, null) }[toX, toY] = cell
    }

    /**
     * Clears every cell on layer [z] (resets all cells to `null`). No-op if
     * layer [z] does not exist.
     */
    fun clearLayer(z: Int) {
        layers[z]?.clear()
    }

    /**
     * Clears every cell on every layer.
     */
    fun clearAllLayers() {
        layers.values.forEach { it.clear() }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns the top-most (highest z) non-null cell at [position], or `null`
     * if all layers are empty at that position.
     *
     * Equivalent to `topCellAt(position.x, position.y)`.
     */
    fun topCellAt(position: Vector2Int): T? = topCellAt(position.x, position.y)

    /**
     * Returns the top-most (highest z) non-null cell at column [x], row [y],
     * or `null` if all layers are empty at that position.
     */
    fun topCellAt(x: Int, y: Int): T? {
        layers.forEach { (_, grid) ->
            val cell = grid[x, y]
            if (cell != null) return cell
        }
        return null
    }

    /**
     * Returns the cell at column [x], row [y] on layer [z] specifically,
     * ignoring all other layers. Returns `null` if layer [z] does not exist or
     * the cell is empty on that layer.
     *
     * Use this when you need per-layer values rather than the composited result
     * from [topCellAt]. Typical use case: preserving layer content across a
     * grid resize.
     */
    fun cellAt(x: Int, y: Int, z: Int): T? = layers[z]?.get(x, y)

    /**
     * Returns the set of z-indices for which a layer has been created.
     *
     * The set is unordered; use [topCellAt] for composited rendering.
     */
    val layerKeys: Set<Int> get() = layers.keys.toSet()
}
