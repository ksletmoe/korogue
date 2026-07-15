package com.sletmoe.kotile.utilities

import java.util.TreeMap

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
    private val layers: TreeMap<Int, Grid<T?>> = TreeMap(compareByDescending { it })

    // Per-position mutation counter (krogue-c0q): bumped whenever a write touches (x, y) on any
    // layer, so a consumer that samples this tilemap through a viewport (render(source, viewport))
    // can detect "changed since I last rendered this cell" by comparing versionAt against its own
    // remembered value -- without a single consumable dirty flag on the tilemap itself, which would
    // break for multiple consumers sharing one source (see ADR-0024's "Alternatives considered").
    // Starts at 0 (matches every cell's initial, never-written state); a real write always produces
    // a version >= 1, so any consumer's "never rendered" sentinel just needs to differ from 0.
    private val version = Grid(width, height, 0)
    private var nextVersion = 1

    /**
     * The mutation count at column [x], row [y] — bumped by any write (on any
     * layer) that touches this position. Consumers compare this against a
     * remembered prior value to detect "this cell changed since I last looked"
     * without consuming a shared signal off the tilemap (krogue-c0q).
     */
    fun versionAt(x: Int, y: Int): Int = version[x, y]

    private fun bump(x: Int, y: Int) {
        version[x, y] = nextVersion++
    }

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
        bump(x, y)
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
        val layer = layers[z] ?: return
        layer[x, y] = null
        bump(x, y)
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
        bump(fromX, fromY)
        layers.getOrPut(toZ) { Grid(width, height, null) }[toX, toY] = cell
        bump(toX, toY)
    }

    /**
     * Clears every cell on layer [z] (resets all cells to `null`). No-op if
     * layer [z] does not exist.
     */
    fun clearLayer(z: Int) {
        val existed = layers[z] != null
        layers[z]?.clear()
        // Bump every position -- clearLayer leaves no trace of which cells were actually populated
        // on this layer, so a consumer sampling any of them must see a version change. A no-op
        // layer (never created) genuinely changes nothing, so skip the bump entirely then.
        if (existed) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    bump(x, y)
                }
            }
        }
    }

    /**
     * Clears every cell on every layer.
     */
    fun clearAllLayers() {
        layers.values.forEach { it.clear() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                bump(x, y)
            }
        }
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

    /**
     * The populated layers ordered from the lowest z (drawn first) to the
     * highest z (drawn last), as a live view over the internal layer map.
     *
     * Intended for **bottom-up composited rendering**: draw each layer's cell in
     * turn so that a higher layer is alpha-blended over the layers beneath it,
     * letting a foreground tile's transparent pixels reveal the tile(s) below.
     * Contrast with [topCellAt], which collapses a cell's stack to only the
     * winning (highest-z) layer.
     */
    val layersBottomUp: Collection<Grid<T?>> get() = layers.descendingMap().values
}
