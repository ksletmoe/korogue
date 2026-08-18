package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.BlendMode
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.FractionalScaleMode
import com.sletmoe.kotile.rendering.GridCompositeCache
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.Layer
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.TileViewport
import com.sletmoe.kotile.rendering.ViewportDirtyTracker
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.kotile.utilities.Vector3Int
import kotlin.math.roundToInt

/**
 * A grid of ASCII cells rendered from a [GlyphSource] (the bundled bitmap
 * [Font], or any other), supporting z-ordered layers for composited output and
 * animated cell content.
 *
 * Cells are set with [drawTile], [drawText], and [fill] (all default to layer
 * z=0 for backwards compatibility), then drawn by [render], which composites
 * every layer bottom-up and redraws each cell once per frame: a background quad
 * tinted by the cell's background color, then the glyph tinted by its
 * foreground color. Create instances with [create]. Owns GPU resources and must
 * be [dispose]d.
 *
 * ## Cell content
 *
 * Both static and time-varying cells are supported. They share the [AsciiTile]
 * sealed interface:
 * - [StaticAsciiTile] — fixed glyph and colors; the most common case.
 * - [DynamicAsciiTile] — resolves to a different appearance as the clock
 *   advances. [AnimatedAsciiTile] is the built-in implementation, cycling
 *   through a sequence of frames to enable Brogue-style effects such as torch
 *   flicker; implement the interface yourself for other time-driven rules.
 *
 * Pass the elapsed wall-clock time to [render] to drive either. Cells holding a
 * [DynamicAsciiTile] are redrawn every frame; static cells are cached and
 * repainted only when written.
 *
 * ## Coordinate system
 *
 * Cell coordinates use a **top-left origin**: cell (0, 0) is the top-left
 * cell, x increases rightward, y increases downward. This is the same
 * convention used by [com.sletmoe.kotile.display.KotileCanvas] and
 * [com.sletmoe.kotile.input.KotileInputProcessor].
 *
 * When wiring up mouse input, tile coordinates delivered by
 * [com.sletmoe.kotile.input.KotileInputListener] directly index this grid
 * without any additional transformation.
 *
 * ## Layer semantics
 *
 * Layers are identified by an integer z-index. A higher z value draws on top.
 * Layers are created on demand the first time a cell is written to them.
 *
 * **A cell's two channels resolve independently** (ADR-0030):
 * - **Glyph and foreground** come from the top-most non-null cell — top-cell-wins.
 *   Two glyphs cannot share a cell, so the highest one takes it.
 * - **Background** comes from the top-most cell that actually paints one, i.e.
 *   whose [StaticAsciiTile.backgroundColor] is not fully transparent. A
 *   transparent background means *"I do not paint a background"* and lets the
 *   cell below supply it.
 *
 * That split is what lets an overlay sit *on* the terrain instead of erasing it:
 * a creature on `CLEAR` keeps the floor's color without having to know it. Alpha
 * is not blended between layers — the first cell that paints a background wins
 * outright and its color is used as-is.
 *
 * One consequence drives how you order layers: a cell whose glyph renders as
 * nothing (a space, which is keyed out) still wins the *glyph* channel, so it
 * hides the glyph beneath it even though the background below still shows. To let
 * a lower cell through entirely, [clearTile] rather than writing a blank one —
 * and put a **background-only overlay below** whatever it tints, never above it,
 * or it will blank that glyph.
 *
 * The sprite path ([com.sletmoe.kotile.rendering.TileRenderer]) still differs
 * deliberately: it draws every populated layer bottom-up and alpha-blends them,
 * because sprites are images and blending them is the point. ADR-0029 covers why
 * matching that literally here would be wrong; ADR-0030 covers why resolving per
 * channel is the useful middle ground.
 *
 * Typical usage for a roguelike — only the terrain and the highlight paint a
 * background, and the highlight sits *under* the creature so the creature keeps
 * the glyph:
 * ```
 * window.drawTile(x, y, z = 0, tile = groundTile)    // terrain: opaque background
 * window.drawTile(x, y, z = 1, tile = highlightTile) // effect: opaque bg, blank glyph
 * window.drawTile(x, y, z = 2, tile = creatureTile)  // creature: CLEAR bg, keeps the tint below
 * ```
 * The creature supplies the glyph; the highlight supplies the background; the
 * terrain supplies it when there is no highlight. Swap the last two and the
 * highlight's blank glyph erases the creature.
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
 * ## Resize behavior
 *
 * When [fitToWindow] is `true` (the default), [resize] recomputes the tile
 * grid dimensions from the new pixel size and rebuilds all layer grids. Cells
 * that still fit within the new bounds are preserved per layer; cells outside
 * the new bounds are dropped and new cells default to `null`. When
 * [fitToWindow] is `false`, the grid stays at its construction-time dimensions
 * and is scaled/letterboxed by the canvas to fit the window.
 *
 * To render a windowed slice of a larger logical tile space, use
 * [render(source, viewport)][render] with a consumer-owned
 * `LayeredTilemap<AsciiTile>` and a [TileViewport] describing the
 * top-left origin. Logical cells outside the source bounds are treated as empty.
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
 * @property widthInTiles grid width in cells
 * @property heightInTiles grid height in cells
 */
class AsciiTileWindow private constructor(
    initialGlyphSource: GlyphSource,
    private val canvas: KotileCanvas,
    widthInTiles: Int,
    heightInTiles: Int,
    private val fitToWindow: Boolean,
    private val scalePolicy: ScalePolicy = IntegerScale,
    /** When `false` the canvas was supplied externally and [dispose] must not release it. */
    private val ownsCanvas: Boolean = true,
    /** When `false` the glyph source was supplied externally and [dispose] must not release it. */
    initialOwnsGlyphSource: Boolean = true,
    /**
     * When `true`, this window composites into a canvas shared with other panes, so its cached
     * composite is blitted with [BlendMode.NORMAL] rather than the default authoritative
     * [BlendMode.REPLACE] — the REPLACE blit of an unpopulated (transparent) cell would otherwise
     * erase whatever a neighbor pane drew there (krogue-a24). See [AsciiTileWindowConfig.sharesCanvas].
     */
    sharesCanvas: Boolean = false,
    /**
     * When `true`, the grid keeps a **fixed** cell count and, on each [resize], re-rasterises a
     * size-parametric [glyphSource] at the on-screen cell pixel size (via
     * [GlyphSource.prepareForCellSize]) and renders it 1:1 — so glyphs are drawn *at* the display size
     * rather than scaled to it (ADR-0036 tier 3, krogue-9x7.2). Only meaningful for a size-parametric
     * source such as [FreeTypeGlyphSource]; a bitmap [Font] ignores the re-rasterise and behaves as a
     * normal fixed grid.
     */
    private val resolutionIndependent: Boolean = false,
) : Disposable {
    private val compositeBlend: BlendMode = if (sharesCanvas) BlendMode.NORMAL else BlendMode.REPLACE

    /**
     * The glyph source currently mounted. Replaced by [setGlyphSource] — see there for why a window
     * lets this change at all.
     */
    var glyphSource: GlyphSource = initialGlyphSource
        private set

    /** Whether [dispose] (and a [setGlyphSource] swap) may release the mounted [glyphSource]. */
    private var ownsGlyphSource: Boolean = initialOwnsGlyphSource

    // The pixel size of the last [resize], so [setGlyphSource] can re-prepare a swapped-in
    // size-parametric source for the cell it will actually be drawn at. 0 until the first resize.
    private var lastWidthPx: Int = 0
    private var lastHeightPx: Int = 0

    /**
     * The on-screen cell size in **logical points**, or `null` to size cells automatically.
     *
     * Set it and the window reflows at that exact cell size — the cell *count* follows the window
     * instead of the other way round (see [resizeAtCellSize]); this is the sizing a zoom control wants,
     * and it renders every glyph source at the same on-screen size. Leave it `null` for the automatic
     * behaviour: the source's native size under plain reflow, or the largest cell that fits the fixed
     * grid when [resolutionIndependent]. Takes precedence over both when set.
     *
     * Assigning re-lays out immediately at the last known window size.
     */
    var cellSizePx: Int? = null
        set(value) {
            val sanitised = value?.coerceAtLeast(1)
            if (sanitised == field) return
            field = sanitised
            if (lastWidthPx > 0 && lastHeightPx > 0) resize(lastWidthPx, lastHeightPx)
        }

    /** Current grid width in cells. Updated by [resize] when [fitToWindow] is `true`. */
    var widthInTiles: Int = widthInTiles
        private set

    /** Current grid height in cells. Updated by [resize] when [fitToWindow] is `true`. */
    var heightInTiles: Int = heightInTiles
        private set

    /**
     * Current tile width in pixels. Reflects the font's character width.
     *
     * Use this together with [tileHeightPx], [widthInTiles], and
     * [heightInTiles] to configure a
     * [com.sletmoe.kotile.input.KotileInputProcessor] for pixel-to-tile
     * coordinate translation.
     */
    val tileWidthPx: Int get() = canvas.tileWidthPx

    /**
     * Current tile height in pixels. Reflects the font's character height.
     *
     * Use this together with [tileWidthPx], [widthInTiles], and
     * [heightInTiles] to configure a
     * [com.sletmoe.kotile.input.KotileInputProcessor] for pixel-to-tile
     * coordinate translation.
     */
    val tileHeightPx: Int get() = canvas.tileHeightPx

    /**
     * The current grid placement (visible cell count, on-screen tile size, and
     * centering offset). Pass a provider of this to
     * [com.sletmoe.kotile.input.KotileInputProcessor] so mouse→tile mapping
     * stays correct under scaling and letterboxing.
     */
    val layout: com.sletmoe.kotile.rendering.GridLayout get() = canvas.layout

    private var layeredTiles = LayeredTilemap<AsciiTile>(widthInTiles, heightInTiles)

    // Per-cell composite cache (krogue-drk/krogue-oxi, ADR-0024): [render] and [asLayer] recomposite
    // only the cells marked dirty since the last call, blitting the persistent result as one sprite.
    // `var` (not `val`) so the resolution-independent path can rebuild it at a new tile px when the
    // glyph source re-rasterises on resize (rebuildCaches). Sized from the glyph source's atlas px (not
    // the canvas's layout px): for the usual sources those are equal, but the resolution-independent
    // path deliberately rasterises the atlas at the *backbuffer* cell px while the canvas layout stays
    // logical, so the cache holds full backbuffer detail and HdpiUtils' blit upscaling lands 1:1.
    private var compositeCache = GridCompositeCache(glyphSource.charWidthPx, glyphSource.charHeightPx)

    // Separate cache + per-observer dirty tracker for the render(source, viewport) overload
    // (krogue-c0q): source is caller-owned and may be shared across several windows/panes, so it
    // cannot share compositeCache's write-driven dirty marking (that tilemap never flows through
    // this window's own drawTile/clearTile). ViewportDirtyTracker instead polls
    // LayeredTilemap.versionAt each call — see its doc for why that is safe for multiple observers
    // where a single consumable dirty flag would not be.
    private var viewportCache = GridCompositeCache(glyphSource.charWidthPx, glyphSource.charHeightPx)
    private var viewportDirtyTracker = ViewportDirtyTracker(viewportCache)

    // Positions currently holding an AnimatedAsciiTile on any z-layer. Recomputed from ground truth
    // (not incrementally counted) on every single-cell write touching that position -- see
    // refreshAnimatedTrackingAt -- so it can never drift out of sync with the tilemap. Every render
    // call marks all of these dirty, since an animated tile's resolved appearance can change every
    // frame without a corresponding write (ADR-0024).
    private val animatedPositions = HashSet<Vector2Int>()

    private val backgroundTexture: Texture
    private val backgroundRegion: TextureRegion

    // Guards dispose() against a second call. Unlike GridCompositeCache (whose dispose() nulls its
    // own fields so a repeat call is a safe no-op via `?.dispose()`), the resources released here --
    // a shared KotileCanvas/Font, a plain Texture -- aren't safe to dispose twice on their own.
    private var disposed = false

    init {
        // A 1x1 white texture, tinted per cell and stretched to the tile size —
        // so it is always magnified and never minified. It therefore needs no
        // mipmaps or min-filter tuning (krogue-4ni); the default filter is fine.
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        backgroundTexture = Texture(pixmap)
        backgroundRegion = TextureRegion(backgroundTexture)
        pixmap.dispose()

        // Fixed-grid mode: let the canvas scale/letterbox our fixed cell count
        // to the window. Reflow mode leaves the canvas in its default reflow
        // layout and follows its column/row count on resize.
        //
        // Resolution-independent mode is a fixed grid too, but scaled by IntegerScale so that after
        // re-rasterising the glyph source to the on-screen cell px the grid renders 1:1 (scale 1), not
        // re-scaled. The first re-rasterise happens on the first resize.
        if (resolutionIndependent) {
            canvas.useFixedGrid(this.widthInTiles, this.heightInTiles, IntegerScale)
        } else if (!fitToWindow) {
            canvas.useFixedGrid(this.widthInTiles, this.heightInTiles, scalePolicy)
        }
    }

    // -------------------------------------------------------------------------
    // Write — single cell
    // -------------------------------------------------------------------------

    /**
     * Sets the cell at column [x], row [y] on z-layer 0 to [tile]. Accepts
     * both static [StaticAsciiTile] and [AnimatedAsciiTile] content.
     *
     * Existing callers that do not use layers continue to work unchanged; all
     * writes go to z=0 by default.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(
        x: Int,
        y: Int,
        tile: AsciiTile,
    ) {
        drawTile(x, y, z = 0, tile = tile)
    }

    /**
     * Sets the cell at column [x], row [y] on layer [z] to [tile]. The layer
     * is created on demand if it does not yet exist. Accepts both static
     * [StaticAsciiTile] and [AnimatedAsciiTile] content.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(
        x: Int,
        y: Int,
        z: Int,
        tile: AsciiTile,
    ) {
        layeredTiles.setCell(x, y, z, tile)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /**
     * Re-derives whether ([x], [y]) still hosts a [DynamicAsciiTile] on *any*
     * z-layer, from the tilemap itself rather than incremental bookkeeping —
     * cheap (bounded by layer count) since it only runs on a write, and always
     * correct even when a write replaces or removes the specific layer that
     * used to make this position animated.
     *
     * Keyed on the [DynamicAsciiTile] *branch*, not the built-in
     * [AnimatedAsciiTile]: any tile whose appearance varies with time needs the
     * per-frame redraw, including consumer-supplied implementations.
     */
    private fun refreshAnimatedTrackingAt(
        x: Int,
        y: Int,
    ) {
        val stillAnimated = layeredTiles.anyCellAt(x, y) { it is DynamicAsciiTile }
        val position = Vector2Int(x, y)
        if (stillAnimated) animatedPositions.add(position) else animatedPositions.remove(position)
    }

    /**
     * Sets the cell at [position] (x, y, z) to [tile]. The layer is created
     * on demand if it does not yet exist.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(
        position: Vector3Int,
        tile: AsciiTile,
    ) = drawTile(position.x, position.y, position.z, tile)

    // -------------------------------------------------------------------------
    // Write — text
    // -------------------------------------------------------------------------

    /**
     * Writes [text] starting at ([x], [y]) on layer z=0, one character per
     * cell to the right, in [foreground] over [background]. Characters that
     * fall outside the window are skipped rather than throwing.
     */
    fun drawText(
        x: Int,
        y: Int,
        text: String,
        foreground: Color = Color.WHITE,
        background: Color = Color.BLACK,
    ) {
        drawText(x, y, z = 0, text = text, foreground = foreground, background = background)
    }

    /**
     * Writes [text] starting at ([x], [y]) on layer [z], one character per
     * cell to the right, in [foreground] over [background]. Characters that
     * fall outside the window are skipped rather than throwing.
     */
    fun drawText(
        x: Int,
        y: Int,
        z: Int,
        text: String,
        foreground: Color = Color.WHITE,
        background: Color = Color.BLACK,
    ) {
        if (y !in 0 until heightInTiles) return

        text.forEachIndexed { index, character ->
            val cellX = x + index
            if (cellX in 0 until widthInTiles) {
                drawTile(cellX, y, z, StaticAsciiTile(character, foreground, background))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write — fill
    // -------------------------------------------------------------------------

    /**
     * Sets every cell on layer z=0 to [tile]. Accepts both static
     * [StaticAsciiTile] and [AnimatedAsciiTile] content.
     */
    fun fill(tile: AsciiTile) {
        fill(z = 0, tile = tile)
    }

    /**
     * Sets every cell on layer [z] to [tile]. The layer is created on demand
     * if it does not yet exist.
     */
    fun fill(
        z: Int,
        tile: AsciiTile,
    ) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                layeredTiles.setCell(x, y, z, tile)
                refreshAnimatedTrackingAt(x, y)
            }
        }
        // Every cell changed -- cheaper to mark the whole grid than every cell individually.
        compositeCache.markAllDirty()
    }

    // -------------------------------------------------------------------------
    // Clear — single cell
    // -------------------------------------------------------------------------

    /**
     * Clears the cell at column [x], row [y] on layer z=0 so nothing is drawn
     * there. No-op if layer 0 has never been written to.
     */
    fun clearTile(
        x: Int,
        y: Int,
    ) {
        clearTile(x, y, z = 0)
    }

    /**
     * Clears the cell at column [x], row [y] on layer [z]. No-op if layer [z]
     * does not exist.
     */
    fun clearTile(
        x: Int,
        y: Int,
        z: Int,
    ) {
        layeredTiles.removeCell(x, y, z)
        refreshAnimatedTrackingAt(x, y)
        compositeCache.markCellDirty(x, y)
    }

    /**
     * Clears the cell at [position] (x, y, z). No-op if layer z does not
     * exist.
     */
    fun clearTile(position: Vector3Int) = clearTile(position.x, position.y, position.z)

    // -------------------------------------------------------------------------
    // Clear — layers
    // -------------------------------------------------------------------------

    /**
     * Clears every cell on every layer. Use [clearLayer] to clear only one
     * layer.
     */
    fun clear() {
        layeredTiles.clearAllLayers()
        animatedPositions.clear()
        compositeCache.markAllDirty()
    }

    /**
     * Clears every cell on layer [z]. No-op if layer [z] has never been
     * written to.
     */
    fun clearLayer(z: Int) {
        // Only cells actually populated on this layer can change -- collect them before clearing
        // (removeCell/clearLayer leave no trace of what was there) so the recomposite doesn't have
        // to touch the rest of a possibly-mostly-empty layer (e.g. a UI overlay with a few widgets).
        val affected = mutableListOf<Vector2Int>()
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                if (layeredTiles.cellAt(x, y, z) != null) affected.add(Vector2Int(x, y))
            }
        }
        layeredTiles.clearLayer(z)
        for (position in affected) {
            refreshAnimatedTrackingAt(position.x, position.y)
            compositeCache.markCellDirty(position.x, position.y)
        }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns the composited (top-most non-null) [StaticAsciiTile] at
     * column [x], row [y], or `null` if all layers are empty at that cell.
     *
     * @param elapsedMs wall-clock time used to resolve animated cells to a
     *   concrete descriptor. Defaults to 0 (first frame).
     */
    fun topTileAt(
        x: Int,
        y: Int,
        elapsedMs: Long = 0L,
    ): StaticAsciiTile? = layeredTiles.topCellAt(x, y)?.resolveAt(elapsedMs)

    /**
     * Returns the composited (top-most non-null) [StaticAsciiTile] at
     * [position], or `null` if all layers are empty at that cell.
     */
    fun topTileAt(
        position: Vector2Int,
        elapsedMs: Long = 0L,
    ): StaticAsciiTile? = layeredTiles.topCellAt(position)?.resolveAt(elapsedMs)

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    /**
     * Composites all layers and draws every populated cell to the canvas for
     * this frame.
     *
     * This never clears [canvas] itself — when [AsciiTileWindowConfig.sharesCanvas] is `true` (blit
     * mode [BlendMode.NORMAL], see there), a cell this window has since stopped populating leaves a
     * stale pixel behind (krogue-a24). That happens because [BlendMode.NORMAL] uses alpha blending:
     * transparent texels in the composite cache leave the destination pixels unchanged rather than
     * overwriting them, so opaque content from a previous frame persists. Callers sharing a canvas
     * must either clear it each frame, or explicitly repaint any cell that may become empty with
     * opaque pixels — a redraw of only currently-populated cells is not sufficient.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [AnimatedAsciiTile] cells.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   windows that only use static [StaticAsciiTile] cells.
     */
    fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        drawCachedGrid(elapsedMs)
        canvas.end()
    }

    /**
     * Adapts this window's grid to a composited [Layer] (ADR-0018) so it can be
     * stacked with free layers (effects, pixel-space UI) in a
     * [com.sletmoe.kotile.rendering.LayerStack]. The layer draws every populated
     * cell **without** its own `begin`/`end` — the stack owns the single batch
     * for the frame.
     *
     * The window's grid/text UI stays cell-aligned as before; this only lets the
     * grid participate in a stack alongside pixel-space layers.
     *
     * @param elapsedMs supplies the wall-clock time used to resolve animated
     *   [AnimatedAsciiTile] cells, sampled once per [Layer.render]; defaults to a
     *   constant 0 (first frame) for static grids.
     */
    fun asLayer(elapsedMs: () -> Long = { 0L }): Layer =
        object : Layer {
            override fun render(canvas: KotileCanvas) {
                check(canvas === this@AsciiTileWindow.canvas) {
                    "AsciiTileWindow.asLayer must be composited on the canvas it was built with"
                }
                drawCachedGrid(elapsedMs())
            }
        }

    /** Recomposites dirty cells of [layeredTiles] into [compositeCache], then blits the cache as one sprite. */
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
            // whatever size/offset the current GridLayout dictates. The blend is REPLACE by default
            // (the cache is the complete authoritative composite for this region, so a cell cleared
            // since the last paint — fully transparent in the cache — must overwrite the canvas's
            // stale pixel there rather than alpha-blend and leave it untouched; krogue-drk), but
            // NORMAL when sharesCanvas is true so a shared-canvas pane's empty cells don't erase a
            // neighbor's pixels (krogue-a24).
            val l = canvas.layout
            canvas.drawSprite(
                pxX = 0f,
                pxY = 0f,
                region = region,
                w = l.contentWidthPx,
                h = l.contentHeightPx,
                blend = compositeBlend,
            )
        }
    }

    /**
     * Draws [layeredTiles]'s cell ([x], [y]) — the composited (top-most non-null) descriptor's
     * background quad then glyph — into the composite cache's [GridCompositeCache.TileDrawer]. The
     * cached path's viewport is always the origin (the scrollable-viewport `render(source,
     * viewport)` overload bypasses this cache entirely), so screen and logical coordinates are
     * identical here.
     */
    private fun drawCell(
        x: Int,
        y: Int,
        elapsedMs: Long,
        drawer: GridCompositeCache.TileDrawer,
    ) {
        drawResolvedCell(layeredTiles, x, y, x, y, elapsedMs, drawer)
    }

    /**
     * Draws [map]'s cell at ([logicalX], [logicalY]) to screen cell ([screenX], [screenY]),
     * resolving its two channels independently (ADR-0030): the glyph and foreground come from the
     * top-most non-null cell, the background from the top-most cell that actually paints one.
     *
     * One top-down pass, resolving each cell at most once. [AsciiTile.resolveAt] is
     * consumer-supplied for a [DynamicAsciiTile] and is not promised to be cheap or side-effect
     * free, and this runs for every dirty cell every frame — so resolving the top cell once for its
     * glyph and again while scanning for a background would be both wasteful and rude.
     */
    private fun drawResolvedCell(
        map: LayeredTilemap<AsciiTile>,
        logicalX: Int,
        logicalY: Int,
        screenX: Int,
        screenY: Int,
        elapsedMs: Long,
        drawer: GridCompositeCache.TileDrawer,
    ) {
        var topCell: StaticAsciiTile? = null
        var background: Color? = null
        for (layer in map.layersTopDown) {
            val descriptor = (layer[logicalX, logicalY] ?: continue).resolveAt(elapsedMs)
            // First cell found is the top-most one, so it wins the glyph channel.
            if (topCell == null) topCell = descriptor
            // A fully transparent background means "I do not paint one" -- keep walking down and let
            // a lower cell supply it. Alpha is not blended between layers: the first cell that does
            // paint wins outright and its color is used as-is (ADR-0030).
            if (descriptor.backgroundColor.a > 0f) {
                background = descriptor.backgroundColor
                break // topCell was set on the first iteration, so both channels are resolved
            }
        }
        // Nothing anywhere in the stack -- no glyph and no background, so nothing to draw.
        val descriptor = topCell ?: return
        background?.let { drawer.drawTile(screenX, screenY, backgroundRegion, it) }
        glyphSource.glyph(descriptor.character)?.let { glyph ->
            drawer.drawTile(screenX, screenY, glyph, descriptor.foregroundColor)
        }
    }

    /**
     * Draws a windowed slice of [source] to the canvas for this frame.
     *
     * For each screen cell `(screenX, screenY)` the logical cell sampled is
     * `(viewport.originX + screenX, viewport.originY + screenY)`. Logical cells
     * that fall outside [source]'s bounds are skipped silently — no tile is
     * drawn for those screen positions (they remain at the clear color).
     *
     * The viewport origin stays fixed across [resize] calls; a resize changes
     * the visible cell count but does not move the origin, so the world does not
     * appear to scroll when the window grows or shrinks.
     *
     * @param source the logical tile space to sample from; may be larger than the visible window
     * @param viewport the top-left corner of the visible region in [source] tile coordinates;
     *   defaults to `(0, 0)` which samples from the source's origin
     * @param elapsedMs wall-clock time for resolving any [AnimatedAsciiTile] cells
     *
     * Recomposites only the cells that changed since this window last drew this
     * `source` at this `viewport` (krogue-c0q) — safe even when several windows
     * sample the same `source`, since each tracks its own dirty state rather
     * than consuming a shared signal off the tilemap. A different `source`
     * instance or a changed `viewport` origin redraws everything.
     */
    fun render(
        source: LayeredTilemap<AsciiTile>,
        viewport: TileViewport = TileViewport(),
        elapsedMs: Long = 0L,
    ) {
        canvas.begin()
        viewportCache.ensureSize(widthInTiles, heightInTiles)
        viewportDirtyTracker.markDirtyCells(source, viewport, widthInTiles, heightInTiles) { logicalX, logicalY ->
            // Any layer, not just the top one: since a cell's background is resolved from whichever
            // layer paints it (ADR-0030), a DynamicAsciiTile *underneath* the top cell can change
            // this cell's appearance frame to frame without ever being the top cell. Checking only
            // the top would freeze a dynamic background under a static glyph. Matches how the sprite
            // path tracks the same thing.
            source.anyCellAt(logicalX, logicalY) { it is DynamicAsciiTile }
        }
        viewportCache.recompositeIfDirty { x, y, drawer -> drawViewportCell(source, viewport, x, y, elapsedMs, drawer) }
        viewportDirtyTracker.recordRenderedVersions(source, viewport, widthInTiles, heightInTiles)
        canvas.reapplyViewport()
        viewportCache.cachedRegion?.let { region ->
            val l = canvas.layout
            canvas.drawSprite(
                pxX = 0f,
                pxY = 0f,
                region = region,
                w = l.contentWidthPx,
                h = l.contentHeightPx,
                blend = compositeBlend,
            )
        }
        canvas.end()
    }

    /**
     * Draws [source]'s cell at `viewport`-relative screen position ([x], [y]) — the composited
     * (top-most non-null) descriptor's background quad then glyph.
     */
    private fun drawViewportCell(
        source: LayeredTilemap<AsciiTile>,
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
        drawResolvedCell(source, logicalX, logicalY, x, y, elapsedMs, drawer)
    }

    // -------------------------------------------------------------------------
    // Resize
    // -------------------------------------------------------------------------

    /**
     * Updates the canvas projection to the new pixel dimensions.
     *
     * When [fitToWindow] is `true`, also recomputes [widthInTiles] and
     * [heightInTiles] from the new pixel size and rebuilds all internal layer
     * grids. Existing cell content that still fits within the new dimensions
     * is preserved per layer so that layer transparency is maintained; cells
     * outside the new bounds are dropped.
     *
     * When [fitToWindow] is `false`, only the canvas projection is updated;
     * the tile grid remains unchanged.
     *
     * When `resolutionIndependent` (see [AsciiTileWindowConfig]) the cell **count**
     * is fixed but the cell **pixel size** tracks the window: this recomputes the
     * on-screen cell px, re-rasterises a size-parametric glyph source to match (via
     * [GlyphSource.prepareForCellSize]), retiles the canvas ([KotileCanvas.setNativeTileSize]),
     * and rebuilds the composite caches to the new atlas px — so glyphs are drawn
     * *at* the display size, not scaled. See [resizeResolutionIndependent].
     */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ) {
        lastWidthPx = widthPx
        lastHeightPx = heightPx
        val chosenCell = cellSizePx
        if (chosenCell != null) {
            resizeAtCellSize(widthPx, heightPx, chosenCell)
            return
        }
        if (resolutionIndependent) {
            resizeResolutionIndependent(widthPx, heightPx)
            return
        }
        canvas.resize(widthPx, heightPx)
        if (!fitToWindow) return

        // Follow the canvas's reflow layout so the grid matches the (centered)
        // visible cell count.
        reflowGridToLayout()
    }

    /**
     * Resizes the grid to the canvas's current reflow layout, preserving per-layer content for cells
     * that still fit. Shared by the plain reflow path and [resizeAtCellSize]; a no-op when the cell
     * count is unchanged.
     */
    private fun reflowGridToLayout() {
        // GridLayout.forReflow floors to 0 when the window is smaller than a single cell; the grids and
        // composite caches below cannot be built at zero, so keep a 1x1 grid (which the layout then draws
        // as nothing, the window having no room for it) rather than propagating the degenerate size.
        val newWidthInTiles = canvas.layout.columns.coerceAtLeast(1)
        val newHeightInTiles = canvas.layout.rows.coerceAtLeast(1)
        if (newWidthInTiles == widthInTiles && newHeightInTiles == heightInTiles) return

        val oldLayeredTiles = layeredTiles
        val oldWidth = widthInTiles
        val oldHeight = heightInTiles

        widthInTiles = newWidthInTiles
        heightInTiles = newHeightInTiles

        // Rebuild preserving per-layer content for cells that still fit.
        layeredTiles = LayeredTilemap(widthInTiles, heightInTiles)
        for (z in oldLayeredTiles.layerKeys) {
            for (y in 0 until minOf(oldHeight, heightInTiles)) {
                for (x in 0 until minOf(oldWidth, widthInTiles)) {
                    val cell = oldLayeredTiles.cellAt(x, y, z)
                    if (cell != null) {
                        layeredTiles.setCell(x, y, z, cell)
                    }
                }
            }
        }
        // Grid coordinates shifted/dropped -- rebuild from the new tilemap rather than trying to
        // translate the old position set.
        animatedPositions.clear()
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                refreshAnimatedTrackingAt(x, y)
            }
        }
        compositeCache.markAllDirty()
    }

    /**
     * Reflow at a **caller-chosen** cell size (krogue-l23): cells are exactly [cellLogical] logical
     * points and the cell *count* follows the window, the mirror image of [resizeResolutionIndependent]
     * (which pins the count and derives the size).
     *
     * This is the sizing a zoom control needs, and it is also what makes a glyph-source swap
     * size-stable: because the canvas is told the cell size directly, **every** source renders at the
     * same on-screen cell whether or not it can rasterise itself to order. A size-parametric source
     * ([FreeTypeGlyphSource], [TileSheetGlyphSource]) is prepared at the backbuffer-resolution cell and
     * draws at full detail; a fixed bitmap [Font] ignores that and keeps its native atlas, which the
     * composite blit then magnifies to the same cell — by a whole-number factor, through the cache's
     * nearest-neighbour filter, so it stays crisp rather than blurring.
     */
    private fun resizeAtCellSize(
        widthPx: Int,
        heightPx: Int,
        cellLogical: Int,
    ) {
        // Rasterise at backbuffer resolution so the grid carries true display detail, exactly as the
        // resolution-independent path does; the canvas layout stays in logical points.
        val hidpi = (Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width.coerceAtLeast(1)).coerceAtLeast(1f)
        val cellPhysical = (cellLogical * hidpi).roundToInt().coerceAtLeast(1)
        glyphSource.prepareForCellSize(cellPhysical, cellPhysical)
        if (glyphSource.charWidthPx != compositeCacheTileW || glyphSource.charHeightPx != compositeCacheTileH) {
            rebuildCaches()
        }
        // The chosen size is the *layout* size, not the atlas size: a source whose atlas came back
        // smaller (a bitmap page) is magnified into it by the blit rather than shrinking the cell.
        canvas.setNativeTileSize(cellLogical, cellLogical)
        canvas.resize(widthPx, heightPx)
        reflowGridToLayout()
        compositeCache.markAllDirty()
    }

    /**
     * Resolution-independent resize (krogue-9x7.2): the cell **count** stays fixed; the cell **pixel
     * size** tracks the window. Compute the largest integer cell that fits the fixed grid, re-rasterise
     * the glyph source at that size, retile the canvas + caches to the source's new native px, and let
     * the canvas render 1:1 (IntegerScale). A bitmap source that ignores [GlyphSource.prepareForCellSize]
     * keeps its native px, so this degrades to an ordinary fixed grid.
     */
    private fun resizeResolutionIndependent(
        widthPx: Int,
        heightPx: Int,
    ) {
        // Logical cell px sets the canvas layout (and so input mapping); the atlas is rasterised at the
        // *backbuffer* cell px so the grid is drawn at true display resolution. On a HiDPI display these
        // differ by the backbuffer/logical ratio; on a normal display they're equal.
        val cellLogical = minOf(widthPx / widthInTiles, heightPx / heightInTiles).coerceAtLeast(1)
        val hidpi = (Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width.coerceAtLeast(1)).coerceAtLeast(1f)
        val cellPhysical = (cellLogical * hidpi).roundToInt().coerceAtLeast(1)

        glyphSource.prepareForCellSize(cellPhysical, cellPhysical)
        // The atlas px the source actually produced (a bitmap Font ignores the request and stays put; a
        // freetype source follows it, so its atlas == cellPhysical). The canvas layout is the atlas px
        // mapped back through the HiDPI ratio, so the grid draws at logical scale and HdpiUtils upscales
        // the atlas-resolution cache blit 1:1 onto the backbuffer. For a size-parametric source this is
        // the intended `cellLogical`; a fixed bitmap source just renders at its native backbuffer res.
        val atlasW = glyphSource.charWidthPx
        val atlasH = glyphSource.charHeightPx
        val layoutW = (atlasW / hidpi).roundToInt().coerceAtLeast(1)
        val layoutH = (atlasH / hidpi).roundToInt().coerceAtLeast(1)
        canvas.setNativeTileSize(layoutW, layoutH) // no-op if unchanged
        if (atlasW != compositeCacheTileW || atlasH != compositeCacheTileH) rebuildCaches()
        canvas.resize(widthPx, heightPx)
        compositeCache.markAllDirty()
    }

    /**
     * Mounts a different [GlyphSource] on this window, in place.
     *
     * This is what a **display-mode switch** is made of — the ASCII-versus-tiles option roguelikes
     * conventionally offer (and, just as usefully, swapping between a bitmap code page and a vector face).
     * Without it, changing the look means disposing the window and building a new one, which invalidates
     * every reference a game's UI, input, and screen code holds; with it, the grid, its contents, and the
     * window identity all survive the switch. The three shipped sources are interchangeable here because
     * they share one addressing contract — [GlyphSource.glyph] is indexed by `char.code` — so the cells a
     * game has already written stay meaningful under the new source.
     *
     * Sources may differ in native cell px, so this retiles the canvas, reallocates the composite caches
     * (whose framebuffers are sized from the tile px and cannot resize in place), and re-runs the sizing
     * path at the last [resize] dimensions — which re-rasterises a size-parametric source
     * ([FreeTypeGlyphSource], [TileSheetGlyphSource]) for the cell it will actually be drawn at. Every
     * cell is then marked dirty, since the cached composite depicts the outgoing source's glyphs.
     *
     * Swapping to the already-mounted instance is a no-op.
     *
     * ## Ownership
     *
     * If this window owned the outgoing source it is **disposed** here — so a caller that intends to
     * swap back must either mount it with [owns] `= false` and dispose it itself, or accept that it is
     * gone and build a fresh one. [owns] declares the same contract for the incoming [source]: `true`
     * (the default) hands it to the window, which will dispose it on the next swap or on [dispose];
     * `false` keeps it the caller's to manage, matching [createWithCanvas]'s external-source rule.
     *
     * Building the replacement is not free (a vector face re-renders its whole page, a tilesheet
     * re-downscales every master), so a game that toggles frequently should keep its sources alive and
     * mount them with `owns = false` rather than reconstructing them per switch.
     *
     * @param source the glyph source to mount
     * @param owns whether this window may dispose [source]; see the ownership note above
     */
    fun setGlyphSource(
        source: GlyphSource,
        owns: Boolean = true,
    ) {
        if (source === glyphSource) return
        // Mount first, release second: if the outgoing source throws on dispose, the window is already
        // holding the live replacement rather than a disposed one.
        val outgoing = glyphSource.takeIf { ownsGlyphSource }
        glyphSource = source
        ownsGlyphSource = owns
        outgoing?.dispose()
        // Retile to the incoming source's native px, then resize the caches to match it.
        canvas.setNativeTileSize(source.charWidthPx, source.charHeightPx) // no-op if unchanged
        rebuildCaches()
        // Re-run the real sizing path so a size-parametric source is prepared for the on-screen cell
        // (and the canvas layout follows). Skipped before the first resize, when there is no size yet —
        // the imminent first resize will do it.
        if (lastWidthPx > 0 && lastHeightPx > 0) resize(lastWidthPx, lastHeightPx)
        compositeCache.markAllDirty()
    }

    /** The atlas px (both axes) the composite caches are currently sized at (see [rebuildCaches]). */
    private var compositeCacheTileW = glyphSource.charWidthPx
    private var compositeCacheTileH = glyphSource.charHeightPx

    /**
     * Disposes and reallocates the composite caches at the glyph source's current atlas px. Called by
     * the resolution-independent path after the source re-rasterises, since a [GridCompositeCache]'s
     * framebuffer is sized from the tile px at construction and can't resize in place. The per-observer
     * [ViewportDirtyTracker] is rebuilt with the fresh cache.
     */
    private fun rebuildCaches() {
        compositeCacheTileW = glyphSource.charWidthPx
        compositeCacheTileH = glyphSource.charHeightPx
        compositeCache.dispose()
        compositeCache = GridCompositeCache(glyphSource.charWidthPx, glyphSource.charHeightPx)
        viewportCache.dispose()
        viewportCache = GridCompositeCache(glyphSource.charWidthPx, glyphSource.charHeightPx)
        viewportDirtyTracker = ViewportDirtyTracker(viewportCache)
    }

    // -------------------------------------------------------------------------
    // Dispose
    // -------------------------------------------------------------------------

    /**
     * The [KotileCanvas] this window renders into. Exposed (module-internal) as an
     * ownership-observation seam: a [create]-built window allocates this canvas
     * itself, so a test cannot otherwise reach it to confirm [dispose] released it.
     */
    internal val backingCanvas: KotileCanvas get() = canvas

    /**
     * Releases GPU resources owned by this window.
     *
     * ## Ownership rules
     *
     * - Windows created via [create] with a DSL block own their canvas **and**
     *   their font, and both are disposed here.
     * - Windows created via [createWithCanvas] receive an externally-owned
     *   [KotileCanvas]. That canvas is **not** disposed here — the caller that
     *   supplied it is responsible for disposing it after all windows sharing it
     *   have been disposed.
     * - The [Font] passed to [createWithCanvas] follows the same rule: if you
     *   supply a glyph source it is considered externally owned and will **not** be
     *   disposed by this window.
     *
     * A second call is a no-op (see [disposed]).
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        if (ownsCanvas) canvas.dispose()
        if (ownsGlyphSource) glyphSource.dispose()
        backgroundTexture.dispose()
        compositeCache.dispose()
        viewportCache.dispose()
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Factory for building [AsciiTileWindow] instances. */
    companion object {
        /**
         * Builds an [AsciiTileWindow] from an [AsciiTileWindowConfig]. The
         * canvas tile size is taken from the font. The window **owns** both
         * the canvas and the font and will dispose them when [dispose] is
         * called.
         *
         * ```
         * val window = AsciiTileWindow.create {
         *     widthInTiles = 80
         *     heightInTiles = 30
         * }
         * ```
         */
        fun create(init: AsciiTileWindowConfig.() -> Unit): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            val glyphSource = config.glyphSource ?: Fonts.cp437_10x10()
            val canvas = KotileCanvas(glyphSource.charWidthPx, glyphSource.charHeightPx, config.fractionalScaleMode)

            return AsciiTileWindow(
                glyphSource,
                canvas,
                config.widthInTiles,
                config.heightInTiles,
                config.fitToWindow,
                config.scalePolicy,
                sharesCanvas = config.sharesCanvas,
                resolutionIndependent = config.resolutionIndependent,
            ).apply { cellSizePx = config.cellSizePx }
        }

        /**
         * Builds an [AsciiTileWindow] that shares an externally-owned
         * [KotileCanvas] and [Font].
         *
         * Use this factory when multiple windows must share a single render
         * batch — for example, a multi-pane layout where a map pane and a HUD
         * pane both draw into the same [KotileCanvas]. Both panes must set
         * [AsciiTileWindowConfig.sharesCanvas] `= true`, or each one's REPLACE
         * blit will erase whatever the other already drew (krogue-a24):
         *
         * ```kotlin
         * val font   = Fonts.cp437_10x10()
         * val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)
         *
         * val mapPane = AsciiTileWindow.createWithCanvas(canvas, font) {
         *     widthInTiles  = 60
         *     heightInTiles = 30
         *     sharesCanvas  = true
         * }
         * val hudPane = AsciiTileWindow.createWithCanvas(canvas, font) {
         *     widthInTiles  = 20
         *     heightInTiles = 30
         *     sharesCanvas  = true
         * }
         *
         * // Later — dispose order: windows first, then shared resources.
         * mapPane.dispose()
         * hudPane.dispose()
         * canvas.dispose()
         * font.dispose()
         * ```
         *
         * ## Fixed-grid caveat
         *
         * A [KotileCanvas] has a single layout. A window created with
         * `fitToWindow = false` puts the canvas into fixed-grid mode sized to
         * *its* dimensions. Two windows sharing one canvas with `fitToWindow =
         * false` and **different** dimensions would fight over that one layout
         * (last one built wins), so both would then render at the wrong scale.
         * A shared canvas supports at most one fixed-grid pane; use reflow
         * (`fitToWindow = true`) for multi-pane layouts on a shared canvas.
         *
         * ## Ownership and dispose contract
         *
         * The window created by this factory does **not** own [canvas] or
         * [font]. Calling [dispose] on the window releases only the resources
         * the window allocated internally (the background texture). The caller
         * that created [canvas] and [font] must dispose them **after** all
         * windows that reference them have been disposed.
         *
         * @param canvas the shared [KotileCanvas]; must remain valid for the
         *   entire lifetime of the window
         * @param glyphSource the shared [GlyphSource]; must remain valid for the entire
         *   lifetime of the window
         * @param init configuration block for tile grid dimensions and
         *   [AsciiTileWindowConfig.fitToWindow]
         */
        fun createWithCanvas(
            canvas: KotileCanvas,
            glyphSource: GlyphSource,
            init: AsciiTileWindowConfig.() -> Unit = {},
        ): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            // Resolution independence retiles the canvas (native tile px, atlas, caches). A canvas
            // supplied here is externally owned and generally shared, with a single layout; letting one
            // pane retile it would silently invalidate the others. Not supported via this factory.
            require(!config.resolutionIndependent) {
                "resolutionIndependent is not supported with a shared/external canvas (createWithCanvas); " +
                    "use create { } so the window owns its canvas."
            }
            return AsciiTileWindow(
                initialGlyphSource = glyphSource,
                canvas = canvas,
                widthInTiles = config.widthInTiles,
                heightInTiles = config.heightInTiles,
                fitToWindow = config.fitToWindow,
                scalePolicy = config.scalePolicy,
                ownsCanvas = false,
                initialOwnsGlyphSource = false,
                sharesCanvas = config.sharesCanvas,
            )
        }
    }
}

/**
 * Configuration for [AsciiTileWindow.create] and [AsciiTileWindow.createWithCanvas].
 *
 * @property glyphSource the [GlyphSource] to render with when using
 *   [AsciiTileWindow.create] — the bundled bitmap [Font] or any other;
 *   defaults to [Fonts.cp437_10x10] when `null`. Ignored by
 *   [AsciiTileWindow.createWithCanvas], which takes the source as an explicit
 *   parameter instead.
 * @property widthInTiles initial grid width in cells; used when [fitToWindow]
 *   is `false` or before the first [AsciiTileWindow.resize] call
 * @property heightInTiles initial grid height in cells; used when [fitToWindow]
 *   is `false` or before the first [AsciiTileWindow.resize] call
 * @property fitToWindow when `true` (default), [AsciiTileWindow.resize]
 *   recomputes the tile grid to fit the new pixel dimensions (reflow); when
 *   `false` the grid stays at its construction-time size and is scaled +
 *   letterboxed to the window by [scalePolicy]
 * @property scalePolicy how the fixed grid is scaled to the window when
 *   [fitToWindow] is `false`; defaults to [IntegerScale] (crisp, pixel-perfect).
 *   Ignored when [fitToWindow] is `true`.
 * @property sharesCanvas set `true` when this window composites into a
 *   [KotileCanvas] shared with other panes (other windows or
 *   [com.sletmoe.kotile.rendering.TileRenderer]s drawing into the same canvas).
 *   Its composite is then blitted with [BlendMode.NORMAL] instead of the default
 *   authoritative [BlendMode.REPLACE], so this window's unpopulated cells don't
 *   erase a neighbor pane's pixels (krogue-a24). Correct as long as the frame is
 *   composited fresh each time (every pane redrawn, or the canvas cleared first).
 *   Leave `false` for a window that owns its whole canvas.
 * @property fractionalScaleMode how a **fractional** fixed-grid scale is smoothed
 *   (see [com.sletmoe.kotile.rendering.FractionalScaleMode]). Defaults to
 *   [com.sletmoe.kotile.rendering.FractionalScaleMode.SHARP_BILINEAR]; set
 *   [com.sletmoe.kotile.rendering.FractionalScaleMode.SUPERSAMPLE] to opt into the
 *   tier-2 supersample→gamma-downsample path (ADR-0036, krogue-1zo), which stays
 *   crisp at any window size. Ignored unless the scale is fractional (only
 *   [fitToWindow] `= false` with a fractional [scalePolicy] such as
 *   [com.sletmoe.kotile.rendering.FitScale] reaches it). Only affects a window
 *   built with [AsciiTileWindow.create] (which owns its canvas); with
 *   [AsciiTileWindow.createWithCanvas] the mode is chosen when you construct the
 *   [KotileCanvas].
 * @property cellSizePx fixes the on-screen cell size in logical points and lets the cell **count**
 *   reflow to fit, the mirror of [resolutionIndependent]. The size a zoom control sets, and the way to
 *   make every glyph source draw at the same on-screen size (a bitmap page is magnified into the cell
 *   by a whole-number factor rather than shrinking it). `null` (the default) leaves sizing automatic.
 *   Settable at runtime via [AsciiTileWindow.cellSizePx]; takes precedence over [resolutionIndependent]
 *   and [fitToWindow] when set.
 * @property resolutionIndependent keep a **fixed** cell count but re-rasterise a
 *   size-parametric [glyphSource] at the on-screen cell pixel size on every
 *   [AsciiTileWindow.resize], rendering it 1:1 (ADR-0036 tier 3, krogue-9x7.2). Use
 *   with [FreeTypeGlyphSource] for smooth glyphs rasterised *at* — not scaled to —
 *   any window size. Overrides [scalePolicy]/[fitToWindow] (the grid is a fixed
 *   [IntegerScale] grid). A bitmap [Font] ignores the re-rasterise, so it behaves
 *   as an ordinary fixed grid. Defaults to `false`.
 */
data class AsciiTileWindowConfig(
    var glyphSource: GlyphSource? = null,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
    var fitToWindow: Boolean = true,
    var scalePolicy: ScalePolicy = IntegerScale,
    var sharesCanvas: Boolean = false,
    var fractionalScaleMode: FractionalScaleMode = FractionalScaleMode.SHARP_BILINEAR,
    var resolutionIndependent: Boolean = false,
    var cellSizePx: Int? = null,
)
