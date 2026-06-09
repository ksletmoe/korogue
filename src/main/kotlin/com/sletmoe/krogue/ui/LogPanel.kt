package com.sletmoe.krogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect
import kotlin.math.max

/**
 * A bordered, scrollable message log (ADR-0011): [append] adds a line; the panel shows the most
 * recent lines, newest at the bottom, hard-wrapped to its interior width. PageUp/PageDown scroll
 * back through history; once scrolled to the bottom it auto-follows new lines. The buffer is capped
 * at [maxLines]. In the demo this is driven by the event bus (combat messages) — its first consumer.
 */
class LogPanel(
    override val bounds: IntRect,
    title: String? = null,
    private val fg: Color = Color.LIGHT_GRAY,
    private val bg: Color = Color.BLACK,
    borderColor: Color = Color.GRAY,
    private val z: Int = 0,
    private val maxLines: Int = DEFAULT_MAX_LINES,
) : Widget {
    private val frame = Frame(IntRect(0, 0, bounds.width, bounds.height), title, borderColor, bg, z)
    private val innerWidth = bounds.width - 2
    private val innerHeight = bounds.height - 2

    private val lines = ArrayDeque<String>()

    /** Lines scrolled up from the bottom; 0 = following the newest. Clamped to history in [draw]. */
    private var scrollBack = 0

    /** Adds [line] to the log, evicting the oldest line once past [maxLines]. */
    fun append(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    override fun draw(surface: TileSurface) {
        frame.draw(surface)
        if (innerWidth <= 0 || innerHeight <= 0) return

        val wrapped = lines.flatMap { wrap(it, innerWidth) }
        scrollBack = scrollBack.coerceIn(0, max(0, wrapped.size - innerHeight))
        val end = wrapped.size - scrollBack
        val start = max(0, end - innerHeight)
        for ((row, index) in (start until end).withIndex()) {
            surface.text(1, 1 + row, z, wrapped[index].take(innerWidth), fg, bg)
        }
    }

    override fun handleKey(keycode: Int): Boolean =
        when (keycode) {
            Input.Keys.PAGE_UP -> {
                scrollBack += max(1, innerHeight - 1)
                true
            }
            Input.Keys.PAGE_DOWN -> {
                scrollBack = (scrollBack - max(1, innerHeight - 1)).coerceAtLeast(0)
                true
            }
            else -> false
        }

    /** Hard-wraps [line] into chunks of [width]; a blank line stays one (empty) row. */
    private fun wrap(
        line: String,
        width: Int,
    ): List<String> = if (line.length <= width) listOf(line) else line.chunked(width)

    private companion object {
        const val DEFAULT_MAX_LINES = 200
    }
}
