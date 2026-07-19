package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.utils.viewport.Viewport
import kotlin.math.roundToInt

/**
 * A libGDX [Viewport] that positions a tile grid inside the window by consuming
 * the pure [GridLayout] placement math — it does **not** replace it. The
 * `ScalePolicy`/`GridLayout` module stays the GL-free "brain"; this class only
 * turns the computed layout into a world size + on-screen bounds and applies the
 * GL projection. See `docs/adr/0017-display-scaling-and-resize.md` (krogue-3eu).
 *
 * ## Why a Viewport (correctness, not performance)
 *
 * 1. **HiDPI/retina reconciliation.** [update] is called with **logical** window
 *    dimensions (as libGDX delivers to `ApplicationListener.resize`), and
 *    [apply] routes the on-screen bounds through [HdpiUtils.glViewport], which
 *    scales logical points to backbuffer pixels. On retina / fractional-scaling
 *    displays (e.g. an 800x400 window backed by a 1600x800 framebuffer) the grid
 *    then lands on the real framebuffer instead of relying on a full-backbuffer
 *    default that only happens to line up at clean integer ratios.
 * 2. **Hard-clip letterbox.** The GL viewport is set to the *content* rectangle,
 *    so any overflow (a fixed grid larger than the window at 1x) or partial-tile
 *    bleed is cleanly cut, and the surrounding letterbox bars are guaranteed to
 *    stay at the clear color. (Absorbs the former krogue-vuv.)
 *
 * ## Coordinate system
 *
 * The camera is an [OrthographicCamera] with a **y-up** world whose size is the
 * on-screen content size in logical pixels; the world origin (0, 0) is the
 * content rectangle's bottom-left. Callers keep drawing with a top-left tile
 * origin and apply the y-flip themselves (see
 * [com.sletmoe.kotile.display.KotileCanvas.drawTile]). We deliberately keep the
 * y-up camera + manual flip rather than a y-down world, to avoid inverting
 * texture V-coordinates.
 *
 * The world is the content rectangle only (offsets are expressed as the GL
 * viewport's screen position, not baked into world coordinates), so drawing is
 * content-relative and the letterbox margins are outside the world entirely.
 *
 * @param nativeTileWidthPx a tile's native (pre-scale) width in pixels; must be `> 0`
 * @param nativeTileHeightPx a tile's native (pre-scale) height in pixels; must be `> 0`
 */
class GridViewport(
    private val nativeTileWidthPx: Int,
    private val nativeTileHeightPx: Int,
) : Viewport() {
    private var fixedColumns: Int = 0
    private var fixedRows: Int = 0
    private var scalePolicy: ScalePolicy = IntegerScale

    /**
     * The placement computed on the last [update]: visible column/row count,
     * on-screen (possibly scaled) tile size, and the centering offset. Read this
     * for pixel->tile hit-testing via [GridLayout.tileAt].
     */
    var layout: GridLayout = GridLayout.forReflow(1, 1, nativeTileWidthPx, nativeTileHeightPx)
        private set

    init {
        camera = OrthographicCamera()
    }

    /**
     * Selects **fixed-grid** mode: [columns] x [rows] cells scaled by [policy].
     * Takes effect on the next [update].
     */
    fun useFixedGrid(
        columns: Int,
        rows: Int,
        policy: ScalePolicy,
    ) {
        fixedColumns = columns
        fixedRows = rows
        scalePolicy = policy
    }

    /** Selects **reflow** mode (native-size tiles). Takes effect on the next [update]. */
    fun useReflow() {
        fixedColumns = 0
        fixedRows = 0
    }

    /**
     * Recomputes the [layout] for a [screenWidth] x [screenHeight] **logical**
     * window, sets the world to the content size and the GL bounds to the
     * centered content rectangle, then [apply]s the projection.
     */
    override fun update(
        screenWidth: Int,
        screenHeight: Int,
        centerCamera: Boolean,
    ) {
        val computed =
            if (fixedColumns > 0 && fixedRows > 0) {
                GridLayout.forFixedGrid(
                    screenWidth,
                    screenHeight,
                    fixedColumns,
                    fixedRows,
                    nativeTileWidthPx,
                    nativeTileHeightPx,
                    scalePolicy,
                )
            } else {
                GridLayout.forReflow(screenWidth, screenHeight, nativeTileWidthPx, nativeTileHeightPx)
            }
        layout = computed

        val contentW = computed.contentWidthPx
        val contentH = computed.contentHeightPx
        setWorldSize(contentW, contentH)

        // Screen bounds in *logical* pixels with a GL bottom-left origin; apply()
        // hands these to HdpiUtils.glViewport, which scales them to backbuffer
        // pixels on HiDPI displays. GridLayout offsets use a top-left origin, so
        // the bottom edge sits (offsetY + contentH) from the top.
        val boundsX = computed.offsetXPx.roundToInt()
        val boundsW = contentW.roundToInt()
        val boundsH = contentH.roundToInt()
        val boundsY = screenHeight - computed.offsetYPx.roundToInt() - boundsH
        setScreenBounds(boundsX, boundsY, boundsW, boundsH)

        apply(centerCamera)
    }
}
