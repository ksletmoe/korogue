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
 *   A fully transparent color (e.g. [Color.CLEAR]) does **not** let a lower
 *   z-layer show through: [AsciiTileWindow] composites top-cell-wins, so the
 *   highest-z cell at a position replaces the ones beneath it outright and a
 *   transparent background reveals the canvas clear color, not the layer below
 *   (ADR-0029). `CLEAR` and `BLACK` backgrounds are indistinguishable in the
 *   output for that reason. A cell is an atomic glyph/foreground/background
 *   triple: to draw a creature over terrain, give the creature cell the
 *   background you want the cell to have.
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
