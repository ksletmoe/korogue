package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.TileViewport
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.kotile.utilities.Vector3Int

/**
 * A grid of ASCII cells rendered with a bitmap [Font], supporting z-ordered
 * layers for composited output and animated cell content.
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
 * Both static and animated cells are supported. They share the
 * [AnimatableAsciiTile] sealed interface:
 * - [AsciiTileDescriptor] — fixed glyph and colors; the most common case.
 * - [AnimatedAsciiTile] — cycles through a sequence of descriptors over time,
 *   enabling Brogue-style effects such as torch flicker. Pass the elapsed
 *   wall-clock time to [render] to drive animation.
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
 * Per-cell compositing: the highest-z layer that has a non-null cell at
 * (x, y) wins; lower layers show through where higher layers are empty. This
 * mirrors the permissive policy of
 * [com.sletmoe.kotile.utilities.LayeredTilemap] used by the sprite path.
 *
 * Typical usage for a roguelike:
 * ```
 * window.drawTile(x, y, z = 0, tile = groundDescriptor)   // terrain layer
 * window.drawTile(x, y, z = 1, tile = creatureDescriptor) // creature layer
 * window.drawTile(x, y, z = 2, tile = effectDescriptor)   // effect/highlight
 * ```
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
 * `LayeredTilemap<AnimatableAsciiTile>` and a [TileViewport] describing the
 * top-left origin. Logical cells outside the source bounds are treated as empty.
 *
 * @property widthInTiles grid width in cells
 * @property heightInTiles grid height in cells
 */
class AsciiTileWindow private constructor(
    private val font: Font,
    private val canvas: KotileCanvas,
    widthInTiles: Int,
    heightInTiles: Int,
    private val fitToWindow: Boolean,
    private val scalePolicy: ScalePolicy = IntegerScale,
    /** When `false` the canvas was supplied externally and [dispose] must not release it. */
    private val ownsCanvas: Boolean = true,
    /** When `false` the font was supplied externally and [dispose] must not release it. */
    private val ownsFont: Boolean = true,
) : Disposable {
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

    private var layeredTiles = LayeredTilemap<AnimatableAsciiTile>(widthInTiles, heightInTiles)

    private val backgroundTexture: Texture
    private val backgroundRegion: TextureRegion

    init {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        backgroundTexture = Texture(pixmap)
        backgroundRegion = TextureRegion(backgroundTexture)
        pixmap.dispose()

        // Fixed-grid mode: let the canvas scale/letterbox our fixed cell count
        // to the window. Reflow mode leaves the canvas in its default reflow
        // layout and follows its column/row count on resize.
        if (!fitToWindow) {
            canvas.useFixedGrid(this.widthInTiles, this.heightInTiles, scalePolicy)
        }
    }

    // -------------------------------------------------------------------------
    // Write — single cell
    // -------------------------------------------------------------------------

    /**
     * Sets the cell at column [x], row [y] on z-layer 0 to [tile]. Accepts
     * both static [AsciiTileDescriptor] and [AnimatedAsciiTile] content.
     *
     * Existing callers that do not use layers continue to work unchanged; all
     * writes go to z=0 by default.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(x: Int, y: Int, tile: AnimatableAsciiTile) {
        drawTile(x, y, z = 0, tile = tile)
    }

    /**
     * Sets the cell at column [x], row [y] on layer [z] to [tile]. The layer
     * is created on demand if it does not yet exist. Accepts both static
     * [AsciiTileDescriptor] and [AnimatedAsciiTile] content.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(x: Int, y: Int, z: Int, tile: AnimatableAsciiTile) {
        layeredTiles.setCell(x, y, z, tile)
    }

    /**
     * Sets the cell at [position] (x, y, z) to [tile]. The layer is created
     * on demand if it does not yet exist.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(position: Vector3Int, tile: AnimatableAsciiTile) {
        layeredTiles.setCell(position, tile)
    }

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
                layeredTiles.setCell(cellX, y, z, AsciiTileDescriptor(character, foreground, background))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write — fill
    // -------------------------------------------------------------------------

    /**
     * Sets every cell on layer z=0 to [tile]. Accepts both static
     * [AsciiTileDescriptor] and [AnimatedAsciiTile] content.
     */
    fun fill(tile: AnimatableAsciiTile) {
        fill(z = 0, tile = tile)
    }

    /**
     * Sets every cell on layer [z] to [tile]. The layer is created on demand
     * if it does not yet exist.
     */
    fun fill(z: Int, tile: AnimatableAsciiTile) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                layeredTiles.setCell(x, y, z, tile)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Clear — single cell
    // -------------------------------------------------------------------------

    /**
     * Clears the cell at column [x], row [y] on layer z=0 so nothing is drawn
     * there. No-op if layer 0 has never been written to.
     */
    fun clearTile(x: Int, y: Int) {
        clearTile(x, y, z = 0)
    }

    /**
     * Clears the cell at column [x], row [y] on layer [z]. No-op if layer [z]
     * does not exist.
     */
    fun clearTile(x: Int, y: Int, z: Int) {
        layeredTiles.removeCell(x, y, z)
    }

    /**
     * Clears the cell at [position] (x, y, z). No-op if layer z does not
     * exist.
     */
    fun clearTile(position: Vector3Int) {
        layeredTiles.removeCell(position)
    }

    // -------------------------------------------------------------------------
    // Clear — layers
    // -------------------------------------------------------------------------

    /**
     * Clears every cell on every layer. Use [clearLayer] to clear only one
     * layer.
     */
    fun clear() {
        layeredTiles.clearAllLayers()
    }

    /**
     * Clears every cell on layer [z]. No-op if layer [z] has never been
     * written to.
     */
    fun clearLayer(z: Int) {
        layeredTiles.clearLayer(z)
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns the composited (top-most non-null) [AsciiTileDescriptor] at
     * column [x], row [y], or `null` if all layers are empty at that cell.
     *
     * @param elapsedMs wall-clock time used to resolve animated cells to a
     *   concrete descriptor. Defaults to 0 (first frame).
     */
    fun topDescriptorAt(x: Int, y: Int, elapsedMs: Long = 0L): AsciiTileDescriptor? =
        layeredTiles.topCellAt(x, y)?.descriptorAt(elapsedMs)

    /**
     * Returns the composited (top-most non-null) [AsciiTileDescriptor] at
     * [position], or `null` if all layers are empty at that cell.
     */
    fun topDescriptorAt(position: Vector2Int, elapsedMs: Long = 0L): AsciiTileDescriptor? =
        layeredTiles.topCellAt(position)?.descriptorAt(elapsedMs)

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    /**
     * Composites all layers and draws every populated cell to the canvas for
     * this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [AnimatedAsciiTile] cells.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   windows that only use static [AsciiTileDescriptor] cells.
     */
    fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        renderGrid(layeredTiles, TileViewport(), elapsedMs)
        canvas.end()
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
     */
    fun render(
        source: LayeredTilemap<AnimatableAsciiTile>,
        viewport: TileViewport = TileViewport(),
        elapsedMs: Long = 0L,
    ) {
        canvas.begin()
        renderGrid(source, viewport, elapsedMs)
        canvas.end()
    }

    private fun renderGrid(
        source: LayeredTilemap<AnimatableAsciiTile>,
        viewport: TileViewport,
        elapsedMs: Long,
    ) {
        for (screenY in 0 until heightInTiles) {
            val logicalY = viewport.originY + screenY
            if (logicalY < 0 || logicalY >= source.height) continue
            for (screenX in 0 until widthInTiles) {
                val logicalX = viewport.originX + screenX
                if (logicalX < 0 || logicalX >= source.width) continue
                val cell = source.topCellAt(logicalX, logicalY) ?: continue
                val descriptor = cell.descriptorAt(elapsedMs)

                canvas.drawTile(screenX, screenY, backgroundRegion, descriptor.backgroundColor)
                font.glyph(descriptor.character)?.let { glyph ->
                    canvas.drawTile(screenX, screenY, glyph, descriptor.foregroundColor)
                }
            }
        }
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
     */
    fun resize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        if (!fitToWindow) return

        // Follow the canvas's reflow layout so the grid matches the (centered)
        // visible cell count.
        val newWidthInTiles = canvas.layout.columns
        val newHeightInTiles = canvas.layout.rows
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
    }

    // -------------------------------------------------------------------------
    // Dispose
    // -------------------------------------------------------------------------

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
     *   supply a font it is considered externally owned and will **not** be
     *   disposed by this window.
     */
    override fun dispose() {
        if (ownsCanvas) canvas.dispose()
        if (ownsFont) font.dispose()
        backgroundTexture.dispose()
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
            val font = config.font ?: Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)

            return AsciiTileWindow(
                font,
                canvas,
                config.widthInTiles,
                config.heightInTiles,
                config.fitToWindow,
                config.scalePolicy,
            )
        }

        /**
         * Builds an [AsciiTileWindow] that shares an externally-owned
         * [KotileCanvas] and [Font].
         *
         * Use this factory when multiple windows must share a single render
         * batch — for example, a multi-pane layout where a map pane and a HUD
         * pane both draw into the same [KotileCanvas]:
         *
         * ```kotlin
         * val font   = Fonts.cp437_10x10()
         * val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)
         *
         * val mapPane = AsciiTileWindow.createWithCanvas(canvas, font) {
         *     widthInTiles  = 60
         *     heightInTiles = 30
         * }
         * val hudPane = AsciiTileWindow.createWithCanvas(canvas, font) {
         *     widthInTiles  = 20
         *     heightInTiles = 30
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
         * @param font the shared [Font]; must remain valid for the entire
         *   lifetime of the window
         * @param init configuration block for tile grid dimensions and
         *   [AsciiTileWindowConfig.fitToWindow]
         */
        fun createWithCanvas(
            canvas: KotileCanvas,
            font: Font,
            init: AsciiTileWindowConfig.() -> Unit = {},
        ): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            return AsciiTileWindow(
                font = font,
                canvas = canvas,
                widthInTiles = config.widthInTiles,
                heightInTiles = config.heightInTiles,
                fitToWindow = config.fitToWindow,
                scalePolicy = config.scalePolicy,
                ownsCanvas = false,
                ownsFont = false,
            )
        }
    }
}

/**
 * Configuration for [AsciiTileWindow.create] and [AsciiTileWindow.createWithCanvas].
 *
 * @property font font to render with when using [AsciiTileWindow.create];
 *   defaults to [Fonts.cp437_10x10] when `null`. Ignored by
 *   [AsciiTileWindow.createWithCanvas], which takes the font as an explicit
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
 */
data class AsciiTileWindowConfig(
    var font: Font? = null,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
    var fitToWindow: Boolean = true,
    var scalePolicy: ScalePolicy = IntegerScale,
)
