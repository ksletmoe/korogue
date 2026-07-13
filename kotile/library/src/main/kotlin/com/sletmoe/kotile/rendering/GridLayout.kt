package com.sletmoe.kotile.rendering

/**
 * The computed placement of a tile grid inside a window: how many cells are
 * shown, how big each cell is on screen, and where the grid's top-left corner
 * sits so the grid is centered (with letterbox bars around any leftover space).
 *
 * All pixel fields use a **top-left origin** (y increases downward), matching
 * [com.sletmoe.kotile.display.KotileCanvas] tile coordinates and libGDX Desktop
 * mouse coordinates. The GL y-flip is applied by the canvas at draw time.
 *
 * A layout is produced by one of the two factory functions, corresponding to
 * the two display modes:
 * - [forReflow] — tiles keep their native pixel size; the visible cell count
 *   grows and shrinks with the window ("see more map"). Any sub-tile remainder
 *   is split into centered letterbox margins.
 * - [forFixedGrid] — the cell count is fixed; tiles are scaled by a
 *   [ScalePolicy] to fill the window, preserving aspect ratio and centering the
 *   result.
 *
 * These functions are pure (no GL / libGDX dependency) so the placement math is
 * unit-testable without a graphics context.
 *
 * @property columns number of tile columns displayed
 * @property rows number of tile rows displayed
 * @property tileWidthPx on-screen width of one tile, in pixels (may be fractional)
 * @property tileHeightPx on-screen height of one tile, in pixels (may be fractional)
 * @property offsetXPx x pixel of the grid's left edge within the window (letterbox margin)
 * @property offsetYPx y pixel of the grid's top edge within the window (letterbox margin)
 */
data class GridLayout(
    val columns: Int,
    val rows: Int,
    val tileWidthPx: Float,
    val tileHeightPx: Float,
    val offsetXPx: Float,
    val offsetYPx: Float,
) {
    /** On-screen width of the whole grid in pixels (`columns * tileWidthPx`). */
    val contentWidthPx: Float get() = columns * tileWidthPx

    /** On-screen height of the whole grid in pixels (`rows * tileHeightPx`). */
    val contentHeightPx: Float get() = rows * tileHeightPx

    /**
     * Maps a window pixel position to the tile cell under it, or `null` if the
     * position falls in a letterbox margin or outside the grid.
     *
     * The mapping subtracts the centering offset and divides by the on-screen
     * tile size, so it stays correct under both scaling and letterboxing. Use
     * this for mouse/pointer hit-testing.
     *
     * @param pixelX window pixel x (0 = left edge, increases right)
     * @param pixelY window pixel y (0 = top edge, increases down)
     * @return `(column, row)` with a top-left origin, or `null` if out of bounds
     */
    fun tileAt(pixelX: Float, pixelY: Float): Pair<Int, Int>? {
        if (tileWidthPx <= 0f || tileHeightPx <= 0f) return null
        val localX = pixelX - offsetXPx
        val localY = pixelY - offsetYPx
        if (localX < 0f || localY < 0f) return null
        val column = (localX / tileWidthPx).toInt()
        val row = (localY / tileHeightPx).toInt()
        if (column >= columns || row >= rows) return null
        return column to row
    }

    /**
     * Maps a window pixel position to a **content-pixel** position — the
     * coordinate space of [com.sletmoe.kotile.display.KotileCanvas.drawSprite]
     * and the free (pixel-space) layers built on it — or `null` if the position
     * falls in a letterbox margin or outside the grid content rectangle.
     *
     * This is the free-layer counterpart to [tileAt] (ADR-0018): grid layers map
     * a pixel to a *cell*; free layers (pixel-space UI, effects) hit-test against
     * the *content-pixel* point returned here. The mapping only subtracts the
     * centering offset — content pixels share the grid's on-screen scale and
     * letterbox, so `(0, 0)` is the content top-left and the returned point can be
     * compared directly against a widget's [PixelRect]. Use it for pointer
     * hit-testing of free-positioned widgets.
     *
     * @param pixelX window pixel x (0 = left edge, increases right)
     * @param pixelY window pixel y (0 = top edge, increases down)
     * @return content-pixel `(x, y)` with a top-left origin, or `null` if out of bounds
     */
    fun contentPixelAt(pixelX: Float, pixelY: Float): Pair<Float, Float>? {
        if (tileWidthPx <= 0f || tileHeightPx <= 0f) return null
        val localX = pixelX - offsetXPx
        val localY = pixelY - offsetYPx
        if (localX < 0f || localY < 0f) return null
        if (localX >= contentWidthPx || localY >= contentHeightPx) return null
        return localX to localY
    }

    companion object {
        /**
         * Reflow layout: as many whole native-size tiles as fit the window, with
         * the sub-tile remainder split into centered margins so the grid does not
         * anchor to a corner.
         *
         * @param windowWidthPx window (framebuffer) width in pixels
         * @param windowHeightPx window (framebuffer) height in pixels
         * @param nativeTileWidthPx a tile's native width in pixels; must be `> 0`
         * @param nativeTileHeightPx a tile's native height in pixels; must be `> 0`
         */
        fun forReflow(
            windowWidthPx: Int,
            windowHeightPx: Int,
            nativeTileWidthPx: Int,
            nativeTileHeightPx: Int,
        ): GridLayout {
            require(nativeTileWidthPx > 0 && nativeTileHeightPx > 0) {
                "native tile dimensions must be positive: ${nativeTileWidthPx}x$nativeTileHeightPx"
            }
            val columns = maxOf(0, windowWidthPx / nativeTileWidthPx)
            val rows = maxOf(0, windowHeightPx / nativeTileHeightPx)
            val contentW = columns * nativeTileWidthPx
            val contentH = rows * nativeTileHeightPx
            return GridLayout(
                columns = columns,
                rows = rows,
                tileWidthPx = nativeTileWidthPx.toFloat(),
                tileHeightPx = nativeTileHeightPx.toFloat(),
                offsetXPx = (windowWidthPx - contentW) / 2f,
                offsetYPx = (windowHeightPx - contentH) / 2f,
            )
        }

        /**
         * Fixed-grid layout: [columns] x [rows] cells scaled by [policy] to fill
         * the window while preserving aspect ratio, then centered with letterbox
         * margins.
         *
         * @param windowWidthPx window (framebuffer) width in pixels
         * @param windowHeightPx window (framebuffer) height in pixels
         * @param columns fixed grid width in tiles; must be `> 0`
         * @param rows fixed grid height in tiles; must be `> 0`
         * @param nativeTileWidthPx a tile's native width in pixels; must be `> 0`
         * @param nativeTileHeightPx a tile's native height in pixels; must be `> 0`
         * @param policy scaling strategy; defaults to [IntegerScale]
         */
        fun forFixedGrid(
            windowWidthPx: Int,
            windowHeightPx: Int,
            columns: Int,
            rows: Int,
            nativeTileWidthPx: Int,
            nativeTileHeightPx: Int,
            policy: ScalePolicy = IntegerScale,
        ): GridLayout {
            require(columns > 0 && rows > 0) { "grid dimensions must be positive: ${columns}x$rows" }
            require(nativeTileWidthPx > 0 && nativeTileHeightPx > 0) {
                "native tile dimensions must be positive: ${nativeTileWidthPx}x$nativeTileHeightPx"
            }
            val contentNativeW = columns * nativeTileWidthPx
            val contentNativeH = rows * nativeTileHeightPx
            val scale = policy.scale(windowWidthPx, windowHeightPx, contentNativeW, contentNativeH)
            val tileW = nativeTileWidthPx * scale
            val tileH = nativeTileHeightPx * scale
            val contentW = columns * tileW
            val contentH = rows * tileH
            return GridLayout(
                columns = columns,
                rows = rows,
                tileWidthPx = tileW,
                tileHeightPx = tileH,
                offsetXPx = (windowWidthPx - contentW) / 2f,
                offsetYPx = (windowHeightPx - contentH) / 2f,
            )
        }
    }
}
