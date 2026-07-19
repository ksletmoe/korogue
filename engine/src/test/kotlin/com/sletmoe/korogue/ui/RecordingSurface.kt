package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile

/**
 * A headless [TileSurface] for tests: records every [put] so assertions can inspect what a widget
 * drew, where, on which layer, and in what color — no GL context needed.
 *
 * Every write is stored as its [AsciiTile] (a glyph/`fg`/`bg` [put] is captured as a
 * [StaticAsciiTile]), so the fake models kotile's time branch without a clock: [Cell.glyph]/[Cell.fg]/
 * [Cell.bg] resolve the tile's first frame — matching a static cell exactly — while [Cell.resolveAt]
 * lets a test sample a dynamic cell at any wall-clock time (ADR-0033).
 */
class RecordingSurface(
    override val width: Int,
    override val height: Int,
) : TileSurface {
    data class Cell(
        val x: Int,
        val y: Int,
        val z: Int,
        val tile: AsciiTile,
    ) {
        /** The tile's appearance at [elapsedMs] — `this` for a static cell, the active frame for a dynamic one. */
        fun resolveAt(elapsedMs: Long): StaticAsciiTile = tile.resolveAt(elapsedMs)

        private val firstFrame: StaticAsciiTile get() = tile.resolveAt(0)

        val glyph: Char get() = firstFrame.character
        val fg: Color get() = firstFrame.foregroundColor
        val bg: Color get() = firstFrame.backgroundColor
    }

    val puts = mutableListOf<Cell>()

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    ) {
        put(x, y, z, StaticAsciiTile(glyph, fg, bg))
    }

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        tile: AsciiTile,
    ) {
        puts.add(Cell(x, y, z, tile))
    }

    /**
     * The cell that would be visible at ([x], [y]): the highest z, and among equal z the most
     * recently written — mirroring the window's per-layer last-write-wins overwrite. Null if
     * nothing was drawn there.
     */
    fun top(
        x: Int,
        y: Int,
    ): Cell? = puts.asReversed().filter { it.x == x && it.y == y }.maxByOrNull { it.z }
}
