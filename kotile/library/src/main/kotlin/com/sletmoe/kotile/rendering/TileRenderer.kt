package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.BlendMode
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.DynamicSpriteTile
import com.sletmoe.kotile.tiles.SpriteTile
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * Renders a [LayeredTilemap] of [SpriteTile] tiles to a [KotileCanvas]
 * each frame.
 *
 * Both branches of [SpriteTile] are supported:
 * - [StaticSpriteTile] — coordinate-based; [regionFor] is called with the tile
 *   so subclasses can resolve the region from a sheet or other source.
 * - [DynamicSpriteTile] (e.g. [com.sletmoe.kotile.tiles.AnimatedSpriteTile]) —
 *   owns its frames; the tile is asked for its own region at the elapsed
 *   wall-clock time.
 *
 * Tiles are mutated with [drawTile]/[clearTile]/[fill]/[clear] and drawn by
 * [render]. Cells are **composited bottom-up**: every populated z-layer is drawn
 * from the lowest z to the highest, so a foreground tile placed on a higher layer
 * is alpha-blended over the terrain beneath it and its transparent pixels reveal
 * the lower layers (a background terrain tile plus a foreground entity sprite in
 * the same cell). Subclasses implement [regionFor] to map a [StaticSpriteTile] to
 * the texture region representing it; [DynamicSpriteTile] instances resolve their
 * own regions.
 *
 * This is the sprite sibling of
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow] (ADR-0028): the two paths
 * deliberately share the same vocabulary — [widthInTiles]/[heightInTiles],
 * [resize], [drawTile]/[clearTile]/[clear]/[clearLayer]/[fill], [topTileAt],
 * [render], [asLayer] — so intuition transfers between them.
 *
 * ## Layer semantics
 *
 * Layers are identified by an integer z-index; a higher z draws on top, and
 * layers are created on demand the first time a cell is written to them.
 *
 * **Compositing is bottom-up** (ADR-0029): every populated layer at a cell is
 * drawn, lowest z first, and alpha-blended — so a transparent pixel in an upper
 * sprite reveals the sprite beneath it. This is the deliberate opposite of the
 * ASCII path
 * ([AsciiTileWindow][com.sletmoe.kotile.display.ascii.AsciiTileWindow]), where
 * the highest-z cell wins outright and hides the rest. The paths differ because
 * their content does: sprites are images, and blending them is the whole point,
 * whereas an ASCII cell is an atomic glyph/fg/bg triple that two layers cannot
 * meaningfully share.
 *
 * ## clear / fill semantics
 *
 * - [clear] (no args) — clears every cell on every layer.
 * - [clearLayer] — clears every cell on one specific layer; no-op if the layer
 *   has never been written to.
 * - [clearTile] (x, y) — clears the cell at (x, y) on z=0.
 * - [clearTile] (x, y, z) — clears the cell at (x, y) on layer z.
 * - [fill] (tile) — fills every cell on z=0.
 * - [fill] (z, tile) — fills every cell on the specified layer.
 *
 * Call [resize] from the application's resize callback so the internal
 * tilemap is rebuilt to match the new canvas dimensions. Tiles outside the new
 * bounds are dropped; tiles that still fit are preserved.
 *
 * ## Rendering inside your own FrameBuffer
 *
 * Supported: [render] leaves whatever framebuffer and viewport you had bound
 * exactly as it found them, so you can wrap it in your own
 * [com.badlogic.gdx.graphics.glutils.FrameBuffer] to render to a texture,
 * post-process the frame, drive a screen transition, or grab a screenshot
 * (krogue-s5h). This has to be stated because libGDX itself does not behave that
 * way — its framebuffers do not nest — so kotile restores the binding for you.
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
     * and is updated by [resize].
     */
    val widthInTiles: Int get() = canvas.width

    /**
     * Current grid height in tiles. Reflects the canvas at construction time
     * and is updated by [resize].
     */
    val heightInTiles: Int get() = canvas.height

    /**
     * A tile's native width in pixels (pre-scaling).
     *
     * Use this together with [tileHeightPx], [widthInTiles], and
     * [heightInTiles] to configure a
     * [com.sletmoe.kotile.input.KotileInputProcessor] for pixel-to-tile
     * coordinate translation.
     */
    val tileWidthPx: Int get() = canvas.tileWidthPx

    /**
     * A tile's native height in pixels (pre-scaling).
     *
     * Use this together with [tileWidthPx], [widthInTiles], and
     * [heightInTiles] to configure a
     * [com.sletmoe.kotile.input.KotileInputProcessor] for pixel-to-tile
     * coordinate translation.
     */
    val tileHeightPx: Int get() = canvas.tileHeightPx

    /**
     * The current grid placement (visible tile count, on-screen tile size, and
     * centering offset). Pass a provider of this to
     * [com.sletmoe.kotile.input.KotileInputProcessor] so mouse→tile mapping
     * stays correct under scaling and letterboxing.
     */
    val layout: GridLayout get() = canvas.layout

    private var tilemap = LayeredTilemap<SpriteTile>(widthInTiles, heightInTiles)

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

    // Positions currently holding a DynamicSpriteTile entry on any z-layer. Recomputed from ground
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
    fun resize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        tilemap = LayeredTilemap(widthInTiles, heightInTiles)
        animatedPositions.clear()
        compositeCache.markAllDirty()
    }

    // -------------------------------------------------------------------------
    // Write — single cell
    // -------------------------------------------------------------------------

    /**
     * Places [tile] at column [x], row [y] on z-layer 0. Accepts both
     * [StaticSpriteTile] and [DynamicSpriteTile] content.
     */
    fun drawTile(x: Int, y: Int, tile: SpriteTile) = drawTile(x, y, z = 0, tile = tile)

    /**
     * Places [tile] at column [x], row [y] on z-layer [z]. The layer is created
     * on demand if it does not yet exist. Accepts both [StaticSpriteTile] and
     * [DynamicSpriteTile] content.
     *
     * The same [DynamicSpriteTile] instance may be placed at multiple cells. All
     * cells sharing the same instance show the same animation frame at the same
     * wall-clock time (stateless time model).
     */
    fun drawTile(x: Int, y: Int, z: Int, tile: SpriteTile) {
        tilemap.setCell(x, y, z, tile)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /** Places [tile] at [position] (x, y, z-layer). */
    fun drawTile(position: Vector3Int, tile: SpriteTile) = drawTile(position.x, position.y, position.z, tile)

    // -------------------------------------------------------------------------
    // Write — fill
    // -------------------------------------------------------------------------

    /** Sets every cell on layer z=0 to [tile]. */
    fun fill(tile: SpriteTile) = fill(z = 0, tile = tile)

    /**
     * Sets every cell on layer [z] to [tile]. The layer is created on demand if
     * it does not yet exist.
     */
    fun fill(z: Int, tile: SpriteTile) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                tilemap.setCell(x, y, z, tile)
                refreshAnimatedTrackingAt(x, y)
            }
        }
        // Every cell changed -- cheaper to mark the whole grid than every cell individually.
        compositeCache.markAllDirty()
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    /**
     * Removes the tile at column [x], row [y] on z-layer 0. No-op if layer 0
     * has never been written to.
     */
    fun clearTile(x: Int, y: Int) = clearTile(x, y, z = 0)

    /** Removes the tile at column [x], row [y] on z-layer [z]. No-op if layer [z] does not exist. */
    fun clearTile(x: Int, y: Int, z: Int) {
        tilemap.removeCell(x, y, z)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /** Removes the tile at [position] (x, y, z-layer). No-op if layer z does not exist. */
    fun clearTile(position: Vector3Int) = clearTile(position.x, position.y, position.z)

    /** Clears every cell on every layer. Use [clearLayer] to clear only one layer. */
    fun clear() {
        tilemap.clearAllLayers()
        animatedPositions.clear()
        compositeCache.markAllDirty()
    }

    /** Clears every cell on layer [z]. No-op if layer [z] has never been written to. */
    fun clearLayer(z: Int) {
        // Only cells actually populated on this layer can change -- collect them before clearing
        // (removeCell/clearLayer leave no trace of what was there) so the recomposite doesn't have
        // to touch the rest of a possibly-mostly-empty layer (e.g. a sparse effects overlay).
        val affected = mutableListOf<Vector2Int>()
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                if (tilemap.cellAt(x, y, z) != null) affected.add(Vector2Int(x, y))
            }
        }
        tilemap.clearLayer(z)
        for (position in affected) {
            refreshAnimatedTrackingAt(position.x, position.y)
            compositeCache.markCellDirty(position.x, position.y)
        }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns the top-most (highest-z) non-null [SpriteTile] at column [x], row
     * [y], or `null` if every layer is empty at that cell.
     *
     * Unlike the ASCII path's
     * [topTileAt][com.sletmoe.kotile.display.ascii.AsciiTileWindow.topTileAt],
     * this is *not* the whole composited appearance of the cell: sprite cells are
     * drawn bottom-up and alpha-blended, so lower layers may still be visible
     * beneath what is returned here. It answers "what is on top", not "what does
     * this cell look like" — the latter has no tile-shaped answer on this path,
     * since a [StaticSpriteTile]'s region is resolved by [regionFor].
     */
    fun topTileAt(x: Int, y: Int): SpriteTile? = tilemap.topCellAt(x, y)

    /** Returns the top-most (highest-z) non-null [SpriteTile] at [position], or `null` if empty. */
    fun topTileAt(position: Vector2Int): SpriteTile? = tilemap.topCellAt(position)

    /**
     * Re-derives whether ([x], [y]) still hosts an animated [DynamicSpriteTile] on *any*
     * z-layer, from the tilemap itself rather than incremental bookkeeping —
     * cheap (bounded by layer count) since it only runs on a write, and always
     * correct even when a write replaces or removes the specific layer that
     * used to make this position animated.
     */
    private fun refreshAnimatedTrackingAt(x: Int, y: Int) {
        val stillAnimated = tilemap.anyCellAt(x, y) { it is DynamicSpriteTile }
        val position = Vector2Int(x, y)
        if (stillAnimated) animatedPositions.add(position) else animatedPositions.remove(position)
    }

    /**
     * Composites every populated layer of the internal tilemap (bottom-up) to
     * the canvas for this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [DynamicSpriteTile] (animated) entries.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   renderers that only use [StaticSpriteTile].
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
     *   [DynamicSpriteTile] frames, sampled once per [Layer.render]; defaults to a constant 0
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
        compositeCache.ensureSize(widthInTiles, heightInTiles)
        for (position in animatedPositions) compositeCache.markCellDirty(position.x, position.y)
        compositeCache.recompositeIfDirty { x, y, drawer -> drawCell(x, y, elapsedMs, drawer) }
        // Re-assert this canvas's own viewport + projection before the blit below. The FBO pass
        // above no longer clobbers the viewport -- GridCompositeCache restores what it found
        // (krogue-s5h), so this is now a cheap idempotent re-assert rather than the repair ADR-0024
        // originally needed it to be. Kept deliberately: the blit's correctness shouldn't depend on
        // a guarantee made by a collaborator, and reapplyViewport also restores the batch's
        // projection matrix, which the cache never promised anything about.
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
                is StaticSpriteTile -> drawer.drawTile(x, y, regionFor(entry), entry.tint, entry.flipX, entry.flipY)
                is DynamicSpriteTile -> drawer.drawTile(x, y, entry.regionFor(elapsedMs), entry.tintFor(elapsedMs), entry.flipX, entry.flipY)
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
     * The viewport origin stays fixed across [resize] calls; a resize changes
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
     * @param elapsedMs wall-clock time for resolving any animated [DynamicSpriteTile] entries
     */
    fun render(
        source: LayeredTilemap<SpriteTile>,
        viewport: TileViewport = TileViewport(),
        elapsedMs: Long = 0L,
    ) {
        canvas.begin()
        viewportCache.ensureSize(widthInTiles, heightInTiles)
        viewportDirtyTracker.markDirtyCells(source, viewport, widthInTiles, heightInTiles) { logicalX, logicalY ->
            source.anyCellAt(logicalX, logicalY) { it is DynamicSpriteTile }
        }
        viewportCache.recompositeIfDirty { x, y, drawer -> drawViewportCell(source, viewport, x, y, elapsedMs, drawer) }
        viewportDirtyTracker.recordRenderedVersions(source, viewport, widthInTiles, heightInTiles)
        canvas.reapplyViewport()
        viewportCache.cachedRegion?.let { region ->
            val l = canvas.layout
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = l.contentWidthPx, h = l.contentHeightPx, blend = BlendMode.REPLACE)
        }
        canvas.end()
    }

    /** Draws [source]'s cell at `viewport`-relative screen position ([x], [y]) — every populated layer bottom-up. */
    private fun drawViewportCell(
        source: LayeredTilemap<SpriteTile>,
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
                is StaticSpriteTile -> drawer.drawTile(x, y, regionFor(entry), entry.tint, entry.flipX, entry.flipY)
                is DynamicSpriteTile -> drawer.drawTile(x, y, entry.regionFor(elapsedMs), entry.tintFor(elapsedMs), entry.flipX, entry.flipY)
                null -> {}
            }
        }
    }

    /** Returns the texture region that represents [staticTile]. */
    protected abstract fun regionFor(staticTile: StaticSpriteTile): TextureRegion
}
