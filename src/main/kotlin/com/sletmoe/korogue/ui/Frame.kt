package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A bordered box (ADR-0011): draws a single-line CP437 border around its [bounds] with an optional
 * [title] on the top edge. The interior is left untouched, so a caller draws content into
 * `bounds.inset(1)` (typically on a higher layer). Used by the status bar, dialogs, the log, and
 * the inventory panel. A border needs at least 2x2; smaller bounds draw nothing.
 */
class Frame(
    override val bounds: IntRect,
    private val title: String? = null,
    private val fg: Color = Color.GRAY,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
) : Widget {
    override fun draw(surface: TileSurface) {
        val w = bounds.width
        val h = bounds.height
        if (w < 2 || h < 2) return
        val right = w - 1
        val bottom = h - 1

        surface.put(0, 0, z, Cp437.TOP_LEFT, fg, bg)
        surface.put(right, 0, z, Cp437.TOP_RIGHT, fg, bg)
        surface.put(0, bottom, z, Cp437.BOTTOM_LEFT, fg, bg)
        surface.put(right, bottom, z, Cp437.BOTTOM_RIGHT, fg, bg)
        for (x in 1 until right) {
            surface.put(x, 0, z, Cp437.HORIZONTAL, fg, bg)
            surface.put(x, bottom, z, Cp437.HORIZONTAL, fg, bg)
        }
        for (y in 1 until bottom) {
            surface.put(0, y, z, Cp437.VERTICAL, fg, bg)
            surface.put(right, y, z, Cp437.VERTICAL, fg, bg)
        }

        if (title != null && w > 4) {
            surface.text(1, 0, z, " $title ".take(w - 2), fg, bg)
        }
    }
}
