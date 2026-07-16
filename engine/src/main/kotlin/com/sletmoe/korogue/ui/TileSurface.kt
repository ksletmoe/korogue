package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color

/**
 * The seam the UI toolkit draws through (ADR-0011): a z-layered, cell-addressable sink.
 * Widgets write cells here and never touch the renderer directly, so the real backing
 * ([WindowSurface] over a kotile `AsciiTileWindow`) can be swapped for a fake in tests —
 * making the toolkit's layout and drawing logic headlessly testable.
 *
 * Coordinates are top-left origin (matching the window). Writes outside `[0, width) x
 * [0, height)` are silently dropped. Higher [put] `z` draws on top, resolved per channel
 * the way the backing window resolves it (ADR-0030): the top-most `z` supplies the glyph
 * and [put]'s `fg`, while `bg` comes from the top-most `z` whose background is not fully
 * transparent. So a widget can pass a transparent `bg` to draw over whatever is beneath it
 * without restating that color.
 */
interface TileSurface {
    val width: Int
    val height: Int

    /** Sets the cell at ([x], [y]) on layer [z] to [glyph] in [fg] over [bg]. Out-of-bounds is a no-op. */
    fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    )
}

/** Writes [text] left-to-right starting at ([x], [y]) on layer [z], one char per cell. */
fun TileSurface.text(
    x: Int,
    y: Int,
    z: Int,
    text: String,
    fg: Color,
    bg: Color = Color.BLACK,
) {
    text.forEachIndexed { index, character -> put(x + index, y, z, character, fg, bg) }
}

/** Fills the whole surface on layer [z] with [glyph]/[fg]/[bg] (e.g. a panel background). */
fun TileSurface.fill(
    z: Int,
    glyph: Char,
    fg: Color,
    bg: Color,
) {
    for (y in 0 until height) {
        for (x in 0 until width) put(x, y, z, glyph, fg, bg)
    }
}
