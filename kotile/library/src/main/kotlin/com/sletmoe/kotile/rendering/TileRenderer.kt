package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.BlendMode
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.SpriteTileEntry
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.Tile
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * Renders a [LayeredTilemap] of [SpriteTileEntry] tiles to a [KotileCanvas]
 * each frame.
 *
 * Two concrete tile types are supported:
 * - [StaticTile] — coordinate-based; [regionFor] is called with the tile so
 *   subclasses can resolve the region from a sheet or other source.
 * - [Tile] (e.g. [com.sletmoe.kotile.tiles.AnimatedSpriteTile]) — owns its
 *   frames; [regionFor] is called with the elapsed wall-clock time so the tile
 *   can return the current animation frame.
 *
 * Tiles are mutated with [drawTile]/[clearTile] and drawn by [render], which
 * redraws the whole grid every frame. Cells are **composited bottom-up**: every
 * populated z-layer is drawn from the lowest z to the highest, so a foreground
 * tile placed on a higher layer is alpha-blended over the terrain beneath it and
 * its transparent pixels reveal the lower layers (a background terrain tile plus
 * a foreground entity sprite in the same cell). Subclasses implement [regionFor]
 * to map a [StaticTile] to the texture region representing it; [Tile] instances
 * resolve their own regions.
 *
 * Call [onResize] from the application's resize callback so the internal
 * tilemap is rebuilt to match the new canvas dimensions. Tiles outside the new
 * bounds are dropped; tiles that still fit are preserved.
 *
 * To render a windowed slice of a larger logical tile space, use the
 * [render(source, viewport)][render] overload with a consumer-owned
 * [LayeredTilemap] and a [TileViewport] describing the top-left origin.
 * Logical cells outside the source bounds are skipped silently.
 *
 * @param canvas the canvas tiles are drawn to
 */
abstract class TileRenderer(protected val canvas: KotileCanvas) : Disposable {
    /**
     * Current grid width in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    val windowWidth: Int get() = canvas.width

    /**
     * Current grid height in tiles. Reflects the canvas at construction time
     * and is updated by [onResize].
     */
    val windowHeight: Int get() = canvas.height

    private var tilemap = LayeredTilemap<SpriteTileEntry>(windowWidth, windowHeight)

    // Per-cell composite cache (krogue-drk/krogue-oxi, ADR-0024): [render] and [asLayer] recomposite
    // only the cells marked dirty since the last call, blitting the persistent result as one sprite.
    private val compositeCache = GridCompositeCache(canvas.tileWidthPx, canvas.tileHeightPx)

    // Separate cache + per-observer dirty tracker for the render(source, viewport) overload
    // (krogue-c0q): source is caller-owned and may be shared across several renderers/panes, so it
    // cannot share compositeCache's write-driven dirty marking (that tilemap never flows through
    // this renderer's own drawTile/clearTile). ViewportDirtyTracker instead polls
    // LayeredTilemap.versionAt each call — see its doc for why that is safe for multiple observers
    // where a single consumable dirty flag would not be.
    private val viewportCache = GridCompositeCache(canvas.tileWidthPx, canvas.tileHeightPx)
    private val viewportDirtyTracker = ViewportDirtyTracker(viewportCache)

    // Positions currently holding a Tile (animated) entry on any z-layer. Recomputed from ground
    // truth (not incrementally counted) on every single-cell write touching that position -- see
    // refreshAnimatedTrackingAt -- so it can never drift out of sync with the tilemap. Every render
    // call marks all of these dirty, since an animated tile's resolved appearance can change every
    // frame without a corresponding write (ADR-0024).
    private val animatedPositions = HashSet<Vector2Int>()

    /**
     * Rebuilds the internal tilemap to fit the new pixel dimensions. Tiles
     * outside the new bounds are dropped; those still within bounds are
     * preserved. Call this from the application's resize callback.
     */
    fun onResize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        tilemap = LayeredTilemap(windowWidth, windowHeight)
        animatedPositions.clear()
        compositeCache.markAllDirty()
    }

    /** Places [staticTile] at [position] (x, y, z-layer). */
    fun drawTile(position: Vector3Int, staticTile: StaticTile) = drawTile(position.x, position.y, position.z, staticTile)

    /** Places [staticTile] at column [x], row [y] on z-layer [z]. */
    fun drawTile(x: Int, y: Int, z: Int, staticTile: StaticTile) {
        tilemap.setCell(x, y, z, staticTile)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /**
     * Places an animated [tile] at [position] (x, y, z-layer).
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    fun drawTile(position: Vector3Int, tile: Tile) = drawTile(position.x, position.y, position.z, tile)

    /**
     * Places an animated [tile] at column [x], row [y] on z-layer [z].
     *
     * The same [Tile] instance may be placed at multiple cells. All cells
     * sharing the same instance will show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    fun drawTile(x: Int, y: Int, z: Int, tile: Tile) {
        tilemap.setCell(x, y, z, tile)
        animatedPositions.add(Vector2Int(x, y))
        compositeCache.markCellDirty(x, y)
    }

    /** Removes the tile at [position] (x, y, z-layer). */
    fun clearTile(position: Vector3Int) = clearTile(position.x, position.y, position.z)

    /** Removes the tile at column [x], row [y] on z-layer [z]. */
    fun clearTile(x: Int, y: Int, z: Int) {
        tilemap.removeCell(x, y, z)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /**
     * Re-derives whether ([x], [y]) still hosts an animated [Tile] on *any*
     * z-layer, from the tilemap itself rather than incremental bookkeeping —
     * cheap (bounded by layer count) since it only runs on a write, and always
     * correct even when a write replaces or removes the specific layer that
     * used to make this position animated.
     */
    private fun refreshAnimatedTrackingAt(x: Int, y: Int) {
        val stillAnimated = tilemap.layerKeys.any { z -> tilemap.cellAt(x, y, z) is Tile }
        val position = Vector2Int(x, y)
        if (stillAnimated) animatedPositions.add(position) else animatedPositions.remove(position)
    }

    /**
     * Composites every populated layer of the internal tilemap (bottom-up) to
     * the canvas for this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [Tile] (animated) entries.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   renderers that only use [StaticTile].
     */
    fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        drawCachedGrid(elapsedMs)
        canvas.end()
    }

    /**
     * Adapts this renderer's internal tilemap to a composited [Layer] (ADR-0018)
     * so it can be stacked under free layers (effects, pixel-space UI) in a
     * [LayerStack]. The layer draws the whole grid **without** its own
     * `begin`/`end` — the stack owns the single batch for the frame.
     *
     * @param elapsedMs supplies the wall-clock time used to resolve animated
     *   [Tile] frames, sampled once per [Layer.render]; defaults to a constant 0
     *   (first frame) for static grids.
     */
    fun asLayer(elapsedMs: () -> Long = { 0L }): Layer = object : Layer {
        override fun render(canvas: KotileCanvas) {
            check(canvas === this@TileRenderer.canvas) {
                "TileRenderer.asLayer must be composited on the canvas it was built with"
            }
            drawCachedGrid(elapsedMs())
        }
    }

    /** Recomposites dirty cells of [tilemap] into [compositeCache], then blits the cache as one sprite. */
    private fun drawCachedGrid(elapsedMs: Long) {
        compositeCache.ensureSize(windowWidth, windowHeight)
        for (position in animatedPositions) compositeCache.markCellDirty(position.x, position.y)
        compositeCache.recompositeIfDirty { x, y, drawer -> drawCell(x, y, elapsedMs, drawer) }
        // The FBO pass above (if it ran) clobbered the global GL viewport; restore this canvas's own
        // before drawing the blit below, or it lands at the wrong offset/scale (ADR-0024).
        canvas.reapplyViewport()
        compositeCache.cachedRegion?.let { region ->
            // On-screen (possibly scaled/letterboxed) size, matching how KotileCanvas.drawTile
            // places individual cells — the cache is captured at native resolution but blitted at
            // whatever size/offset the current GridLayout dictates. REPLACE, not the default NORMAL
            // blend: the cache is already the complete authoritative composite for this region, so a
            // cell cleared since the last paint (fully transparent in the cache) must overwrite the
            // canvas's stale pixel there rather than alpha-blend and leave it untouched (krogue-drk).
            val l = canvas.layout
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = l.contentWidthPx, h = l.contentHeightPx, blend = BlendMode.REPLACE)
        }
    }

    /**
     * Draws [tilemap]'s cell ([x], [y]) — every populated layer bottom-up — into the composite
     * cache's [GridCompositeCache.TileDrawer]. The cached path's viewport is always the origin (the
     * scrollable-viewport `render(source, viewport)` overload bypasses this cache entirely), so
     * screen and logical coordinates are identical here.
     */
    private fun drawCell(x: Int, y: Int, elapsedMs: Long, drawer: GridCompositeCache.TileDrawer) {
        for (layer in tilemap.layersBottomUp) {
            when (val entry = layer[x, y]) {
                is StaticTile -> drawer.drawTile(x, y, regionFor(entry), entry.tint, entry.flipX, entry.flipY)
                is Tile -> drawer.drawTile(x, y, entry.regionFor(elapsedMs), entry.tintFor(elapsedMs), entry.flipX, entry.flipY)
                null -> {}
            }
        }
    }

    /** Releases the offscreen composite caches' GPU resources. Safe to call even if never rendered. */
    override fun dispose() {
        compositeCache.dispose()
        viewportCache.dispose()
    }

    /**
     * Draws a windowed slice of [source] to the canvas for this frame.
     *
     * For each screen cell `(screenX, screenY)` the logical cell sampled is
     * `(viewport.originX + screenX, viewport.originY + screenY)`. Logical cells
     * that fall outside [source]'s bounds are skipped silently — no tile is
     * drawn for those screen positions.
     *
     * The viewport origin stays fixed across [onResize] calls; a resize changes
     * the visible cell count but does not move the origin, so the world does
     * not appear to scroll when the window grows or shrinks.
     *
     * Recomposites only the cells that changed since this renderer last drew
     * this `source` at this `viewport` (krogue-c0q) — safe even when several
     * renderers sample the same `source`, since each tracks its own dirty state
     * rather than consuming a shared signal off the tilemap. A different
     * `source` instance or a changed `viewport` origin redraws everything.
     *
     * @param source the logical tile space to sample from; may be larger than the visible window
     * @param viewport the top-left corner of the visible region in [source] tile coordinates;
     *   defaults to `(0, 0)` which samples from the source's origin
     * @param elapsedMs wall-clock time for resolving any animated [Tile] entries
     */
    fun render(
        source: LayeredTilemap<SpriteTileEntry>,
        viewport: TileViewport = TileViewport(),
        elapsedMs: Long = 0L,
    ) {
        canvas.begin()
        viewportCache.ensureSize(windowWidth, windowHeight)
        viewportDirtyTracker.markDirtyCells(source, viewport, windowWidth, windowHeight) { logicalX, logicalY ->
            source.layerKeys.any { z -> source.cellAt(logicalX, logicalY, z) is Tile }
        }
        viewportCache.recompositeIfDirty { x, y, drawer -> drawViewportCell(source, viewport, x, y, elapsedMs, drawer) }
        viewportDirtyTracker.recordRenderedVersions(source, viewport, windowWidth, windowHeight)
        canvas.reapplyViewport()
        viewportCache.cachedRegion?.let { region ->
            val l = canvas.layout
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = l.contentWidthPx, h = l.contentHeightPx, blend = BlendMode.REPLACE)
        }
        canvas.end()
    }

    /** Draws [source]'s cell at `viewport`-relative screen position ([x], [y]) — every populated layer bottom-up. */
    private fun drawViewportCell(
        source: LayeredTilemap<SpriteTileEntry>,
        viewport: TileViewport,
        x: Int,
        y: Int,
        elapsedMs: Long,
        drawer: GridCompositeCache.TileDrawer,
    ) {
        val logicalY = viewport.originY + y
        if (logicalY < 0 || logicalY >= source.height) return
        val logicalX = viewport.originX + x
        if (logicalX < 0 || logicalX >= source.width) return
        for (layer in source.layersBottomUp) {
            when (val entry = layer[logicalX, logicalY]) {
                is StaticTile -> drawer.drawTile(x, y, regionFor(entry), entry.tint, entry.flipX, entry.flipY)
                is Tile -> drawer.drawTile(x, y, entry.regionFor(elapsedMs), entry.tintFor(elapsedMs), entry.flipX, entry.flipY)
                null -> {}
            }
        }
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticTile): TextureRegion
}
