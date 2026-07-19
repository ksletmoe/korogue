package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.LayeredTilemap

/**
 * Marks a [GridCompositeCache]'s dirty cells for the `render(source, viewport)`
 * overloads on [TileRenderer]/[com.sletmoe.kotile.display.ascii.AsciiTileWindow]
 * — the caller-owned, possibly-shared tilemap path ADR-0024 explicitly left
 * uncached (krogue-c0q closes that gap).
 *
 * Unlike the internal-tilemap path, writes to `source` don't flow through this
 * renderer's own API, so there is no push notification to mark cells dirty on
 * write. Instead this **polls**: each visible cell's
 * [LayeredTilemap.versionAt] is compared against what was last rendered there
 * by *this* tracker. That per-observer state is what makes it safe for the
 * same `source` to be sampled by several renderers/panes at once — each keeps
 * its own remembered versions rather than consuming a signal off the tilemap
 * itself, which is exactly the trap ADR-0024 rejected a single consumable
 * dirty flag for.
 *
 * A changed [TileViewport] origin, or a different `source` instance entirely
 * (by reference), invalidates everything — the same "viewport scroll dirties
 * everything" simplification the design already accepted elsewhere, and it
 * sidesteps needing to translate a remembered-version grid when the mapping
 * from screen cell to logical cell shifts. [isAnimatedAt] additionally forces
 * a cell dirty regardless of version, mirroring why the internal-tilemap path
 * tracks animated positions: an animated tile's resolved appearance can change
 * every frame without a corresponding write.
 */
internal class ViewportDirtyTracker(private val cache: GridCompositeCache) {
    private var lastSource: LayeredTilemap<*>? = null
    private var lastOriginX = 0
    private var lastOriginY = 0
    private var lastVersions: Grid<Int>? = null
    private var gridWidth = -1
    private var gridHeight = -1

    /**
     * Marks [cache]'s dirty cells for this render call. Call once per
     * `render(source, viewport)` invocation, before
     * [GridCompositeCache.recompositeIfDirty].
     */
    fun markDirtyCells(
        source: LayeredTilemap<*>,
        viewport: TileViewport,
        windowWidth: Int,
        windowHeight: Int,
        isAnimatedAt: (logicalX: Int, logicalY: Int) -> Boolean,
    ) {
        if (windowWidth != gridWidth || windowHeight != gridHeight) {
            gridWidth = windowWidth
            gridHeight = windowHeight
            // -1 never matches a real version (versions start at 0 for an unwritten cell, 1+ after
            // any write), so every cell mismatches on the first comparison against a fresh grid --
            // a resize alone (same source, same viewport origin) still forces a full redraw at the
            // new size without needing its own special case.
            lastVersions = Grid(windowWidth, windowHeight, -1)
        }

        val sourceChanged = source !== lastSource
        val viewportChanged = viewport.originX != lastOriginX || viewport.originY != lastOriginY
        if (sourceChanged || viewportChanged) {
            cache.markAllDirty()
        } else {
            val versions = lastVersions!!
            for (y in 0 until windowHeight) {
                val logicalY = viewport.originY + y
                if (logicalY < 0 || logicalY >= source.height) continue
                for (x in 0 until windowWidth) {
                    val logicalX = viewport.originX + x
                    if (logicalX < 0 || logicalX >= source.width) continue
                    val current = source.versionAt(logicalX, logicalY)
                    if (versions[x, y] != current || isAnimatedAt(logicalX, logicalY)) {
                        cache.markCellDirty(x, y)
                    }
                }
            }
        }

        lastSource = source
        lastOriginX = viewport.originX
        lastOriginY = viewport.originY
    }

    /**
     * Records the versions actually visible this call, so the next
     * [markDirtyCells] call can compare against them. Call once per render,
     * after the recomposite (whether or not it actually ran).
     */
    fun recordRenderedVersions(
        source: LayeredTilemap<*>,
        viewport: TileViewport,
        windowWidth: Int,
        windowHeight: Int,
    ) {
        val versions = lastVersions ?: return
        for (y in 0 until windowHeight) {
            val logicalY = viewport.originY + y
            if (logicalY < 0 || logicalY >= source.height) continue
            for (x in 0 until windowWidth) {
                val logicalX = viewport.originX + x
                if (logicalX < 0 || logicalX >= source.width) continue
                versions[x, y] = source.versionAt(logicalX, logicalY)
            }
        }
    }
}
