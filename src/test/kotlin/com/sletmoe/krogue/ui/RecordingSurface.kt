package com.sletmoe.krogue.ui

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

    /** The highest-z cell recorded at ([x], [y]), or null if nothing was drawn there. */
    fun top(
        x: Int,
        y: Int,
    ): Cell? = puts.filter { it.x == x && it.y == y }.maxByOrNull { it.z }
}
