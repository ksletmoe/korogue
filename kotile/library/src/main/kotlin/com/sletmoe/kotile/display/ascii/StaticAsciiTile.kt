package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color

/**
 * A single ASCII cell with a fixed glyph and colors — the static branch of
 * [AsciiTile], and the form every cell resolves to for a given moment.
 *
 * [resolveAt] always returns `this`, regardless of elapsed time. For a cell
 * whose glyph or colors change over time, use [AnimatedAsciiTile] (or another
 * [DynamicAsciiTile]) instead.
 *
 * @property character the glyph to draw
 * @property foregroundColor color the glyph is tinted with
 * @property backgroundColor color filling the cell behind the glyph; use a
 *   fully transparent color (e.g. [Color.CLEAR]) to overlay text without
 *   obscuring whatever is already drawn
 */
public data class StaticAsciiTile(
    val character: Char,
    val foregroundColor: Color = Color.WHITE,
    val backgroundColor: Color = Color.BLACK,
) : AsciiTile {
    /**
     * Returns `this` regardless of [elapsedMs] — a static cell does not
     * animate.
     */
    override fun resolveAt(elapsedMs: Long): StaticAsciiTile = this
}
