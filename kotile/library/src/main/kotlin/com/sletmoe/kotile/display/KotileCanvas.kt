package com.sletmoe.kotile.display

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.rendering.GridLayout
import com.sletmoe.kotile.rendering.GridViewport
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.ScalePolicy

/**
 * Draws tile-sized texture regions onto the screen via a batched [SpriteBatch].
 *
 * ## Coordinate system
 *
 * **Tile coordinates** use a **top-left origin**: tile (0, 0) is the top-left
 * cell of the canvas, x increases rightward, and y increases downward.
 *
 * Internally, tile (x, y) is mapped to GL screen coordinates (bottom-left
 * origin, y up) before drawing. This mapping is an implementation detail and
 * is not visible to callers of [drawTile].
 *
 * **Mouse pixel coordinates** from `Gdx.input` (via libGDX Desktop /
 * `InputProcessor`) also use a top-left origin (0, 0 = top-left of window, y
 * increases downward). They are therefore in the same axis orientation as tile
 * coordinates and do **not** need a y-axis flip before pixel-to-tile mapping.
 * See [layout] and [com.sletmoe.kotile.rendering.GridLayout.tileAt] for the
 * offset/scale-aware conversion.
 *
 * Draw calls must be made between [begin] and [end]. Instances own GPU
 * resources and must be [dispose]d.
 *
 * ## Display modes and scaling
 *
 * The canvas places the tile grid within the window using a [GridLayout],
 * recomputed on every [resize]. Two modes are supported:
 *
 * - **Reflow** (default): tiles keep their native pixel size ([tileWidthPx] x
 *   [tileHeightPx]); the visible tile count grows and shrinks with the window,
 *   and any sub-tile remainder is split into centered letterbox margins.
 * - **Fixed grid** (via [useFixedGrid]): the tile count is fixed; tiles are
 *   scaled by a [ScalePolicy] to fill the window while preserving aspect ratio,
 *   then centered with letterbox margins.
 *
 * In both modes the grid is centered; leftover window space is left at the
 * clear color. Placement and the GL projection are driven by a [GridViewport],
 * which sets the GL viewport to the content rectangle via `HdpiUtils.glViewport`:
 * this reconciles logical points with backbuffer pixels on HiDPI/retina displays
 * and **hard-clips** overflow (a fixed grid larger than the window at 1x) and
 * partial-tile bleed, so the letterbox bars are guaranteed to stay at the clear
 * color.
 *
 * The class is `open` to allow subclassing — for example, in tests that need
 * to track dispose calls, or in consumers that want to add instrumentation.
 *
 * @property tileWidthPx a tile's **native** width in pixels (pre-scaling)
 * @property tileHeightPx a tile's **native** height in pixels (pre-scaling)
 */
open class KotileCanvas(val tileWidthPx: Int, val tileHeightPx: Int) : Disposable {
    private val batch = SpriteBatch()
    private val viewport = GridViewport(tileWidthPx, tileHeightPx)

    /**
     * Current placement of the grid within the window: visible column/row
     * count, on-screen (possibly scaled) tile size, and the centering offset.
     * Recomputed on every [resize] and mode change. Use [GridLayout.tileAt] to
     * map a mouse pixel position to a tile cell under the current layout.
     */
    val layout: GridLayout get() = viewport.layout

    /** Current drawable width in pixels (the application's framebuffer width). */
    val widthPx: Int get() = Gdx.graphics.width

    /** Current drawable height in pixels (the application's framebuffer height). */
    val heightPx: Int get() = Gdx.graphics.height

    /** Number of tile columns currently displayed (see [layout]). */
    val width: Int get() = layout.columns

    /** Number of tile rows currently displayed (see [layout]). */
    val height: Int get() = layout.rows

    init {
        resize(widthPx, heightPx)
    }

    /**
     * Switches to **fixed-grid** mode: [columns] x [rows] cells scaled by
     * [policy] to fill the window (preserving aspect ratio) and centered with
     * letterbox margins. Recomputes the [layout] immediately.
     *
     * A canvas has exactly **one** [layout]. If several windows share this
     * canvas (see [com.sletmoe.kotile.display.ascii.AsciiTileWindow.createWithCanvas]),
     * at most one of them may drive a fixed grid — the last call wins, so
     * driving fixed grids of *different* dimensions from two panes makes them
     * fight (both then render/hit-test with whichever dimensions were set last).
     */
    fun useFixedGrid(columns: Int, rows: Int, policy: ScalePolicy = IntegerScale) {
        viewport.useFixedGrid(columns, rows, policy)
        recomputeLayout()
    }

    /**
     * Switches to **reflow** mode (the default): native-size tiles, visible
     * count derived from the window, sub-tile remainder centered. Recomputes
     * the [layout] immediately.
     */
    fun useReflow() {
        viewport.useReflow()
        recomputeLayout()
    }

    /**
     * Updates the projection to a [widthPx] x [heightPx] viewport and recomputes
     * the grid [layout]. Call this from the application's resize callback with the
     * **logical** window size (as libGDX delivers to `ApplicationListener.resize`);
     * the [GridViewport] reconciles it to backbuffer pixels on HiDPI displays.
     */
    fun resize(widthPx: Int, heightPx: Int) {
        viewport.update(widthPx, heightPx, true)
        batch.projectionMatrix = viewport.camera.combined
    }

    private fun recomputeLayout() {
        viewport.update(widthPx, heightPx, true)
        batch.projectionMatrix = viewport.camera.combined
    }

    /**
     * Begins a batch of [drawTile] calls. Re-applies this canvas's [GridViewport]
     * first so the GL viewport (a global) is set to *this* canvas's content
     * rectangle even when several canvases share the frame.
     */
    fun begin() {
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        batch.begin()
    }

    /** Ends the current batch, flushing it to the screen. */
    fun end() = batch.end()

    /**
     * Draws [region] in the cell at column [x], row [y] (top-left origin),
     * scaled to the current on-screen tile size and offset by the layout's
     * centering margin, then multiplied by [tint] (white = unchanged). Must be
     * called between [begin] and [end].
     */
    fun drawTile(x: Int, y: Int, region: TextureRegion, tint: Color = Color.WHITE) {
        batch.color = tint
        val tileW = layout.tileWidthPx
        val tileH = layout.tileHeightPx
        // Coordinates are content-relative: the GridViewport places the content
        // rectangle within the window (the centering offset is the GL viewport's
        // position, not baked in here). Flip to the viewport's y-up world: row 0
        // sits at the top, so its bottom edge is contentHeight - tileH.
        val px = x * tileW
        val screenY = layout.contentHeightPx - (y + 1) * tileH
        batch.draw(region, px, screenY, tileW, tileH)
    }

    /** Disposes the underlying sprite batch. */
    override fun dispose() = batch.dispose()
}
