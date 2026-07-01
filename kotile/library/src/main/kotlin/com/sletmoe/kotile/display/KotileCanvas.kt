package com.sletmoe.kotile.display

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.rendering.GridLayout
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
 * coordinates and do **not** need a y-axis flip before pixel-to-tile division.
 * See [com.sletmoe.kotile.input.pixelToTile] for the conversion.
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
 * clear color (soft letterboxing). Overflow when a fixed grid is larger than
 * the window at 1x is not hard-clipped — see krogue-n64 follow-ups.
 *
 * The class is `open` to allow subclassing — for example, in tests that need
 * to track dispose calls, or in consumers that want to add instrumentation.
 *
 * @property tileWidthPx a tile's **native** width in pixels (pre-scaling)
 * @property tileHeightPx a tile's **native** height in pixels (pre-scaling)
 */
open class KotileCanvas(val tileWidthPx: Int, val tileHeightPx: Int) : Disposable {
    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()

    private var fixedColumns: Int = 0
    private var fixedRows: Int = 0
    private var scalePolicy: ScalePolicy = IntegerScale

    /**
     * Current placement of the grid within the window: visible column/row
     * count, on-screen (possibly scaled) tile size, and the centering offset.
     * Recomputed on every [resize] and mode change. Use [GridLayout.tileAt] to
     * map a mouse pixel position to a tile cell under the current layout.
     */
    var layout: GridLayout = GridLayout.forReflow(1, 1, tileWidthPx, tileHeightPx)
        private set

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
     */
    fun useFixedGrid(columns: Int, rows: Int, policy: ScalePolicy = IntegerScale) {
        fixedColumns = columns
        fixedRows = rows
        scalePolicy = policy
        recomputeLayout()
    }

    /**
     * Switches to **reflow** mode (the default): native-size tiles, visible
     * count derived from the window, sub-tile remainder centered. Recomputes
     * the [layout] immediately.
     */
    fun useReflow() {
        fixedColumns = 0
        fixedRows = 0
        recomputeLayout()
    }

    /**
     * Updates the projection to a [widthPx] x [heightPx] viewport and recomputes
     * the grid [layout]. Call this from the application's resize callback.
     */
    fun resize(widthPx: Int, heightPx: Int) {
        camera.setToOrtho(false, widthPx.toFloat(), heightPx.toFloat())
        batch.projectionMatrix = camera.combined
        recomputeLayout()
    }

    private fun recomputeLayout() {
        val w = widthPx
        val h = heightPx
        layout =
            if (fixedColumns > 0 && fixedRows > 0) {
                GridLayout.forFixedGrid(w, h, fixedColumns, fixedRows, tileWidthPx, tileHeightPx, scalePolicy)
            } else {
                GridLayout.forReflow(w, h, tileWidthPx, tileHeightPx)
            }
    }

    /** Begins a batch of [drawTile] calls. */
    fun begin() = batch.begin()

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
        val px = layout.offsetXPx + x * tileW
        // Flip to GL's bottom-left origin: the top of row y is offsetY + y*tileH
        // from the top, so its bottom edge sits (offsetY + (y+1)*tileH) from the top.
        val screenY = heightPx - (layout.offsetYPx + (y + 1) * tileH)
        batch.draw(region, px, screenY, tileW, tileH)
    }

    /** Disposes the underlying sprite batch. */
    override fun dispose() = batch.dispose()
}
