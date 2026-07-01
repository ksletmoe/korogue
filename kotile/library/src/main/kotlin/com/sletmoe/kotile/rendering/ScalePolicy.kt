package com.sletmoe.kotile.rendering

/**
 * Strategy for how a **fixed** logical tile grid is scaled to fill a window.
 *
 * A scale policy is consulted only in the fixed-grid display mode (see
 * [GridLayout.forFixedGrid]), where the number of visible tiles is constant and
 * the tiles themselves grow or shrink to fill the window while preserving the
 * grid's aspect ratio. It is **not** used by the reflow mode
 * ([GridLayout.forReflow]), where tiles keep their native pixel size and the
 * visible tile count changes instead.
 *
 * The factor is applied to a tile's native pixel dimensions. A content block of
 * `contentWidthPx x contentHeightPx` native pixels (i.e.
 * `columns * nativeTileWidthPx` by `rows * nativeTileHeightPx`) is scaled by the
 * returned factor and then centered/letterboxed within the window by the caller.
 *
 * Built-in implementations:
 * - [IntegerScale] — whole-number factors only; crisp with nearest-neighbor
 *   filtering. This is the default.
 * - [FitScale] — fractional factor that fills as much of the window as possible.
 *
 * See `docs/adr/0017-display-scaling-and-resize.md` for the design and the
 * deferred alternatives (SDF fonts, multi-size asset swap, supersample+FBO).
 */
fun interface ScalePolicy {
    /**
     * Returns the on-screen scale factor for native tile pixels so a
     * [contentWidthPx] x [contentHeightPx] block of native-size content fits
     * within [availableWidthPx] x [availableHeightPx], preserving aspect ratio.
     *
     * Implementations must return a strictly positive factor. Content larger
     * than the window may be returned unscaled (factor `1`) or downscaled,
     * depending on the policy.
     *
     * @param availableWidthPx window (framebuffer) width in pixels; may be `0`
     * @param availableHeightPx window (framebuffer) height in pixels; may be `0`
     * @param contentWidthPx grid width in native tile pixels; must be `> 0`
     * @param contentHeightPx grid height in native tile pixels; must be `> 0`
     */
    fun scale(
        availableWidthPx: Int,
        availableHeightPx: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
    ): Float
}

/**
 * Pixel-perfect scaling: the grid is scaled by the largest **whole number**
 * factor that still fits the window, with a floor of `1`. Pairs with
 * nearest-neighbor texture filtering to keep bitmap glyphs and sprites crisp.
 *
 * The trade-off is chunkier zoom steps and larger letterbox bars than
 * [FitScale], because the factor jumps 1 -> 2 -> 3 rather than filling exactly.
 * When the grid is larger than the window the factor is clamped to `1`, so the
 * content overflows and is clipped rather than shrinking below native size.
 *
 * This is the default policy and the right choice for classic CP437 roguelikes
 * such as the Rogue example.
 */
object IntegerScale : ScalePolicy {
    override fun scale(
        availableWidthPx: Int,
        availableHeightPx: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
    ): Float {
        require(contentWidthPx > 0 && contentHeightPx > 0) {
            "content dimensions must be positive: ${contentWidthPx}x$contentHeightPx"
        }
        // Integer division floors; clamp to at least 1x native.
        val factor = minOf(availableWidthPx / contentWidthPx, availableHeightPx / contentHeightPx)
        return maxOf(1, factor).toFloat()
    }
}

/**
 * Fractional scaling: the grid is scaled by the largest factor that still fits
 * the window on both axes, preserving aspect ratio. Fills more of the window
 * than [IntegerScale] (smaller letterbox bars) at the cost of non-integer tile
 * sizes, which need a smoothing filter (e.g. sharp-bilinear) to avoid uneven
 * glyph strokes.
 *
 * Unlike [IntegerScale], this policy will scale **below** `1` when the grid is
 * larger than the window, shrinking the content so it always fits.
 */
object FitScale : ScalePolicy {
    override fun scale(
        availableWidthPx: Int,
        availableHeightPx: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
    ): Float {
        require(contentWidthPx > 0 && contentHeightPx > 0) {
            "content dimensions must be positive: ${contentWidthPx}x$contentHeightPx"
        }
        val sx = availableWidthPx.toFloat() / contentWidthPx
        val sy = availableHeightPx.toFloat() / contentHeightPx
        return minOf(sx, sy)
    }
}
