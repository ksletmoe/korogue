package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A full-window, vertically centered block of text (ADR-0011): the classic game-over / splash /
 * pause / "you have won" screen. Clears [bounds] to [bg], then paints [lines] centered top-to-bottom
 * (top row = `(height - lines.size) / 2`, so an odd remainder leans up), with [centerHorizontally]
 * choosing between flush-left and per-line horizontal centering. Lines past the bottom are dropped
 * rather than overflowing; each line is truncated to the surface width. All content and colors are
 * caller-supplied — the engine has no opinion on what the screen says.
 *
 * Unlike [PagedTextList] (top-aligned, bordered, paginated for overflow), this widget is full-window
 * and never paginates — content that doesn't fit is simply cropped. Display-only by default: with no
 * [dismissKeys]/[onDismiss] it consumes no input, leaving dismissal to the host (e.g. "any key exits
 * to the title screen"). Supplying both makes the widget swallow the matching keys itself, mirroring
 * [PagedTextList]'s dismiss convention.
 */
class TextOverlay(
    override val bounds: IntRect,
    private val lines: List<String>,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val centerHorizontally: Boolean = false,
    private val dismissKeys: Set<Int> = emptySet(),
    private val onDismiss: (() -> Unit)? = null,
) : Widget {
    override fun draw(surface: TileSurface) {
        surface.fill(z, ' ', fg, bg)
        val top = ((surface.height - lines.size) / 2).coerceAtLeast(0)
        lines.take(surface.height - top).forEachIndexed { row, line ->
            val shown = line.take(surface.width)
            val x = if (centerHorizontally) (surface.width - shown.length) / 2 else 0
            surface.text(x, top + row, z, shown, fg, bg)
        }
    }

    override fun handleKey(keycode: Int): Boolean {
        if (onDismiss == null || keycode !in dismissKeys) return false
        onDismiss.invoke()
        return true
    }
}
