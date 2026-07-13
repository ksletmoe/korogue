package com.sletmoe.kotile.rendering

/**
 * An axis-aligned rectangle in **content-pixel** space — the top-left-origin
 * coordinate space of [com.sletmoe.kotile.display.KotileCanvas.drawSprite] and
 * the free (pixel-space) layers built on it (ADR-0018). Used as a free-positioned
 * [Widget]'s bounds: it drives both where the widget draws and pointer
 * hit-testing against [contains].
 *
 * The x axis increases rightward and y increases downward, matching tile and
 * mouse coordinates. `(0, 0)` is the top-left of the grid content rectangle; map
 * a window pixel into this space with
 * [com.sletmoe.kotile.rendering.GridLayout.contentPixelAt].
 *
 * @property x left edge, content pixels
 * @property y top edge, content pixels
 * @property width width in content pixels
 * @property height height in content pixels
 */
data class PixelRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    /** Right edge (exclusive): `x + width`. */
    val right: Float get() = x + width

    /** Bottom edge (exclusive): `y + height`. */
    val bottom: Float get() = y + height

    /**
     * True if content-pixel point ([px], [py]) lies within this rectangle, with
     * the left/top edges inclusive and the right/bottom edges exclusive
     * (`x <= px < right`, `y <= py < bottom`) — the same half-open convention as
     * pixel-cell coverage and [GridLayout.tileAt], so adjacent widgets that share
     * an edge never both claim a point.
     */
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px < right && py >= y && py < bottom
}
