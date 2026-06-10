package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color

/**
 * Describes a single ASCII cell with a fixed glyph and colors.
 *
 * Implements [AnimatableAsciiTile]; [descriptorAt] always returns `this`
 * regardless of elapsed time. For a cell whose glyph or colors change over
 * time, use [AnimatedAsciiTile] instead.
 *
 * @property character the glyph to draw
 * @property foregroundColor color the glyph is tinted with
 * @property backgroundColor color filling the cell behind the glyph; use a
 *   fully transparent color (e.g. [Color.CLEAR]) to overlay text without
 *   obscuring whatever is already drawn
 */
public data class AsciiTileDescriptor(
    val character: Char,
    val foregroundColor: Color = Color.WHITE,
    val backgroundColor: Color = Color.BLACK,
) : AnimatableAsciiTile {
    /**
     * Returns `this` descriptor regardless of [elapsedMs] — a static cell does
     * not animate.
     */
    override fun descriptorAt(elapsedMs: Long): AsciiTileDescriptor = this
}
