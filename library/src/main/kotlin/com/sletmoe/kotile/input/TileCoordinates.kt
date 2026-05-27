package com.sletmoe.kotile.input

/**
 * Translates a libGDX mouse pixel position to a kotile tile coordinate.
 *
 * ## Coordinate system alignment
 *
 * libGDX Desktop (`InputProcessor` on LWJGL3) reports mouse positions in
 * **screen pixels** with a **top-left origin**: (0, 0) is the top-left corner
 * of the window and y increases downward. This already matches the kotile tile
 * coordinate convention, so **no y-axis flip is required**.
 *
 * Note that this differs from GL/OpenGL conventions (bottom-left origin, y up),
 * which [com.sletmoe.kotile.display.KotileCanvas] uses internally when issuing
 * draw calls. Consumers of the input API never see GL coordinates; all mouse
 * positions are in the top-left-origin screen-pixel space.
 *
 * The conversion is simply:
 * ```
 * tileX = screenPixelX / tileWidthPx
 * tileY = screenPixelY / tileHeightPx
 * ```
 *
 * ## Out-of-bounds policy
 *
 * Returns `null` when the resulting tile coordinates fall outside the range
 * `[0, gridWidthInTiles)` × `[0, gridHeightInTiles)`. This covers:
 *
 * - Clicks in letterboxed black bars (when `fitToWindow = false` and the
 *   window is larger than the tile grid).
 * - Clicks in the partial-tile strip at the right or bottom edge when the
 *   pixel dimensions are not an exact multiple of the tile size.
 * - Any negative pixel values (which libGDX can report for off-window events).
 *
 * @param screenPixelX screen pixel X from libGDX (0 = left edge, right)
 * @param screenPixelY screen pixel Y from libGDX (0 = top edge, down)
 * @param tileWidthPx current tile width in pixels
 * @param tileHeightPx current tile height in pixels
 * @param gridWidthInTiles number of tile columns (used for bounds check)
 * @param gridHeightInTiles number of tile rows (used for bounds check)
 * @return tile (x, y) with top-left origin, or `null` if out of bounds
 */
fun pixelToTile(
    screenPixelX: Int,
    screenPixelY: Int,
    tileWidthPx: Int,
    tileHeightPx: Int,
    gridWidthInTiles: Int,
    gridHeightInTiles: Int,
): Pair<Int, Int>? {
    // Guard against negative values before integer division (which would
    // truncate toward zero, incorrectly landing inside the grid for e.g. -1).
    if (screenPixelX < 0 || screenPixelY < 0) return null

    val tileX = screenPixelX / tileWidthPx
    val tileY = screenPixelY / tileHeightPx

    if (tileX >= gridWidthInTiles) return null
    if (tileY >= gridHeightInTiles) return null

    return tileX to tileY
}
