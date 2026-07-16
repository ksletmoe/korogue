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
 * @property backgroundColor color filling the cell behind the glyph.
 *
 *   A fully transparent color (e.g. [Color.CLEAR]) means *"do not paint a
 *   background"*, and the cell below supplies one instead: [AsciiTileWindow]
 *   resolves a cell's background from the top-most layer that actually paints
 *   one, independently of the glyph (ADR-0030). So a creature drawn on `CLEAR`
 *   over terrain keeps the terrain's background without needing to know it, and
 *   `CLEAR` is meaningfully different from `BLACK`, which paints black.
 *
 *   If no layer at that position paints a background, none is drawn and the
 *   canvas clear color shows through. Alpha is not blended between layers — the
 *   first cell that paints wins and its color is used as-is.
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
