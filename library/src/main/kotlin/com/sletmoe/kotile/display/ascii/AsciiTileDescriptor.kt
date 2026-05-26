package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color

/**
 * Describes a single ASCII cell.
 *
 * @property character the glyph to draw
 * @property foregroundColor color the glyph is tinted with
 * @property backgroundColor color filling the cell behind the glyph; use a
 *   fully transparent color (e.g. [Color.CLEAR]) to overlay text without
 *   obscuring whatever is already drawn
 */
data class AsciiTileDescriptor(
    val character: Char,
    val foregroundColor: Color = Color.WHITE,
    val backgroundColor: Color = Color.BLACK,
)
