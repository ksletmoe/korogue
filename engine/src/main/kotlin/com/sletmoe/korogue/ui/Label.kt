package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A single line of text (ADR-0011): draws [text] — re-read each frame, so it can be live — on the
 * top row of [bounds], truncated to the available width. The toolkit's simplest widget, for status
 * lines, captions, and HUD readouts.
 *
 * For scrolling, multi-line, event-fed text use [LogPanel]; for a bordered box use [Frame].
 */
class Label(
    override val bounds: IntRect,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val text: () -> String,
) : Widget {
    override fun draw(surface: TileSurface) {
        surface.text(0, 0, z, text().take(surface.width), fg, bg)
    }
}
