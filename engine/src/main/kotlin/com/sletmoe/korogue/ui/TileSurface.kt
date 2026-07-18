package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile

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
 *
 * ## Static and time-varying cells
 *
 * The primitive is the glyph/`fg`/`bg` [put] — a fixed cell, the overwhelmingly common case.
 * The [AsciiTile] overload widens the seam to kotile's time branch (ADR-0033): a
 * [DynamicAsciiTile] (e.g. an `AnimatedAsciiTile` torch flicker) whose appearance the backing
 * window resolves per frame from its own clock, so a widget can place a cell that animates
 * without threading elapsed time through `draw` itself. The overload's **default** resolves
 * the tile's first frame ([AsciiTile.resolveAt]`(0)`) and forwards it as a static cell, so a
 * surface that has no clock — a headless fake, a game's custom sink — degrades to the tile's
 * static appearance rather than needing to model time. Only a surface actually backed by a
 * per-frame render loop ([WindowSurface], and [RegionSurface] on its way there) overrides it
 * to carry the live dynamic cell through. Faithful-Rogue needs none of this (original Rogue has
 * no animated cells); it exists so a korogue game *can* express Brogue-style flicker.
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

    /**
     * Sets the cell at ([x], [y]) on layer [z] to [tile], which may be a [DynamicAsciiTile] whose
     * appearance varies with time. Out-of-bounds is a no-op.
     *
     * The default forwards the tile's first frame ([AsciiTile.resolveAt]`(0)`) through the
     * glyph/`fg`/`bg` [put] — correct for a [StaticAsciiTile] (it resolves to itself) and a safe
     * still-frame fallback for a dynamic one. A surface that renders per frame overrides this to
     * hand the live tile to its backing window so the animation actually plays.
     */
    fun put(
        x: Int,
        y: Int,
        z: Int,
        tile: AsciiTile,
    ) {
        val frame = tile.resolveAt(0)
        put(x, y, z, frame.character, frame.foregroundColor, frame.backgroundColor)
    }
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
