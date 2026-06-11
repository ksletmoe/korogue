package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A bordered panel listing the carried items (ADR-0011). Reads [items] each frame so it tracks the
 * live inventory; shows an empty-state line when there's nothing. Rows past the interior height are
 * not drawn (no scrolling yet — the demo inventory is small). Lines are truncated to fit the width.
 */
class InventoryPanel(
    override val bounds: IntRect,
    title: String? = "Inventory",
    private val fg: Color = Color.LIGHT_GRAY,
    private val bg: Color = Color.BLACK,
    borderColor: Color = Color.GRAY,
    private val emptyText: String = "(empty)",
    private val z: Int = 0,
    private val items: () -> List<String>,
) : Widget {
    private val frame = Frame(IntRect(0, 0, bounds.width, bounds.height), title, borderColor, bg, z)
    private val innerWidth = bounds.width - 2
    private val innerHeight = bounds.height - 2

    override fun draw(surface: TileSurface) {
        frame.draw(surface)
        if (innerWidth <= 0 || innerHeight <= 0) return

        val rows = items().ifEmpty { listOf(emptyText) }
        rows.take(innerHeight).forEachIndexed { index, line ->
            surface.text(1, 1 + index, z, line.take(innerWidth), fg, bg)
        }
    }
}
