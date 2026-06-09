package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color

/**
 * A headless [TileSurface] for tests: records every [put] so assertions can inspect what a widget
 * drew, where, on which layer, and in what color — no GL context needed.
 */
class RecordingSurface(
    override val width: Int,
    override val height: Int,
) : TileSurface {
    data class Cell(
        val x: Int,
        val y: Int,
        val z: Int,
        val glyph: Char,
        val fg: Color,
        val bg: Color,
    )

    val puts = mutableListOf<Cell>()

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    ) {
        puts.add(Cell(x, y, z, glyph, fg, bg))
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
