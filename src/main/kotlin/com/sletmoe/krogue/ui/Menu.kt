package com.sletmoe.krogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect

/** One selectable row in a [Menu]: a [label] and the [action] run when it is activated. */
class MenuItem(
    val label: String,
    val action: () -> Unit,
)

/**
 * A vertical list of selectable [items] (ADR-0011): Up/Down move the highlight (wrapping), Enter
 * runs the selected item's action. The selected row is drawn full-width in inverted colors. One
 * item per row from the top of [bounds]; rows past the bottom are not drawn. Typically placed
 * inside a [Dialog].
 */
class Menu(
    override val bounds: IntRect,
    private val items: List<MenuItem>,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val selectedFg: Color = Color.BLACK,
    private val selectedBg: Color = Color.WHITE,
    private val z: Int = 0,
) : Widget {
    var selected: Int = 0
        private set

    override fun draw(surface: TileSurface) {
        items.forEachIndexed { index, item ->
            if (index >= surface.height) return@forEachIndexed
            val highlighted = index == selected
            val row = item.label.take(surface.width).padEnd(surface.width)
            surface.text(0, index, z, row, if (highlighted) selectedFg else fg, if (highlighted) selectedBg else bg)
        }
    }

    override fun handleKey(keycode: Int): Boolean =
        when (keycode) {
            Input.Keys.UP -> {
                move(-1)
                true
            }
            Input.Keys.DOWN -> {
                move(1)
                true
            }
            Input.Keys.ENTER -> {
                items.getOrNull(selected)?.action?.invoke()
                true
            }
            else -> false
        }

    private fun move(delta: Int) {
        if (items.isNotEmpty()) selected = (selected + delta + items.size) % items.size
    }
}
