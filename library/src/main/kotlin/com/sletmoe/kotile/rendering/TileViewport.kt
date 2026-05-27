package com.sletmoe.kotile.rendering

/**
 * Represents the top-left origin of a visible window into a larger logical tile
 * space, expressed in tile coordinates.
 *
 * A viewport with `originX = 50, originY = 30` means the renderer will display
 * the region of the logical tile space starting at column 50, row 30. The
 * visible region extends from there to `(originX + windowWidth - 1, originY +
 * windowHeight - 1)`.
 *
 * Fractional (sub-tile) scroll is not supported in this version; consumers who
 * need smooth pixel-level panning should interpolate between integer positions
 * themselves before passing a viewport to the renderer.
 *
 * TODO: zoom support — a scale factor applied around the viewport origin would
 *   allow the consumer to show more or fewer tiles without changing the window's
 *   pixel size.
 *
 * @property originX column in the logical tile space that maps to screen column 0
 * @property originY row in the logical tile space that maps to screen row 0
 */
data class TileViewport(
    val originX: Int = 0,
    val originY: Int = 0,
) {
    /**
     * Returns a new [TileViewport] translated by ([dx], [dy]) tiles. Positive
     * [dx] scrolls right; positive [dy] scrolls down.
     */
    fun translate(dx: Int, dy: Int): TileViewport =
        TileViewport(originX + dx, originY + dy)
}
