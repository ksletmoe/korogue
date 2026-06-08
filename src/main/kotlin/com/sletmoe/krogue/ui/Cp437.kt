package com.sletmoe.krogue.ui

/**
 * CP437 glyphs by code-page index. kotile's font maps a [Char] to a glyph by `char.code` into a
 * 256-cell CP437 sheet, so box-drawing and block characters must be referenced by their CP437
 * byte value (e.g. `Char(196)` = `─`), not their Unicode code point (`'─'` = U+2500 would miss).
 */
object Cp437 {
    val TOP_LEFT = Char(218) // ┌
    val TOP_RIGHT = Char(191) // ┐
    val BOTTOM_LEFT = Char(192) // └
    val BOTTOM_RIGHT = Char(217) // ┘
    val HORIZONTAL = Char(196) // ─
    val VERTICAL = Char(179) // │
    val FULL_BLOCK = Char(219) // █
    val LIGHT_SHADE = Char(176) // ░
}
