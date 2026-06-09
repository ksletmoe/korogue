package com.sletmoe.korogue.ui

import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InventoryPanelTest : FunSpec({

    fun RecordingSurface.row(
        innerRow: Int,
        innerWidth: Int,
    ): String =
        (0 until innerWidth)
            .map { top(1 + it, 1 + innerRow)?.glyph ?: ' ' }
            .joinToString("")
            .trimEnd()

    test("lists the carried items inside the border, reading them live each draw") {
        val items = mutableListOf("potion", "ring")
        val panel = InventoryPanel(IntRect(0, 0, 12, 6)) { items } // inner 10 x 4

        val first = RecordingSurface(12, 6)
        panel.draw(first)
        first.row(0, 10) shouldBe "potion"
        first.row(1, 10) shouldBe "ring"

        items.add("scroll") // changes between frames
        val second = RecordingSurface(12, 6)
        panel.draw(second)
        second.row(2, 10) shouldBe "scroll"
    }

    test("shows an empty-state line when nothing is carried") {
        val panel = InventoryPanel(IntRect(0, 0, 12, 6), emptyText = "(empty)") { emptyList() }
        val surface = RecordingSurface(12, 6)

        panel.draw(surface)

        surface.row(0, 10) shouldBe "(empty)"
    }

    test("draws the border and title") {
        val panel = InventoryPanel(IntRect(0, 0, 12, 6), title = "Inventory") { emptyList() }
        val surface = RecordingSurface(12, 6)

        panel.draw(surface)

        surface.top(0, 0)!!.glyph shouldBe Cp437.TOP_LEFT
        surface.top(2, 0)!!.glyph shouldBe 'I' // title " Inventory " -> 'I' at x=2
    }
})
