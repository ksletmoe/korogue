package com.sletmoe.krogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MenuTest : FunSpec({

    fun menu(vararg labels: String): Pair<Menu, MutableList<String>> {
        val fired = mutableListOf<String>()
        val items = labels.map { label -> MenuItem(label) { fired.add(label) } }
        return Menu(IntRect(0, 0, 10, labels.size), items) to fired
    }

    test("Down moves the selection and wraps; Up wraps the other way") {
        val (m, _) = menu("a", "b", "c")
        m.selected shouldBe 0
        m.handleKey(Input.Keys.DOWN)
        m.selected shouldBe 1
        m.handleKey(Input.Keys.DOWN)
        m.handleKey(Input.Keys.DOWN)
        m.selected shouldBe 0 // wrapped past the end
        m.handleKey(Input.Keys.UP)
        m.selected shouldBe 2 // wrapped before the start
    }

    test("Enter runs the selected item's action") {
        val (m, fired) = menu("Resume", "Quit")
        m.handleKey(Input.Keys.DOWN) // select "Quit"
        m.handleKey(Input.Keys.ENTER)
        fired shouldBe listOf("Quit")
    }

    test("navigation and activation keys are consumed; others are not") {
        val (m, _) = menu("a", "b")
        m.handleKey(Input.Keys.DOWN) shouldBe true
        m.handleKey(Input.Keys.ENTER) shouldBe true
        m.handleKey(Input.Keys.LEFT) shouldBe false
    }

    test("the selected row is drawn full-width in inverted colors") {
        val (m, _) = menu("ab", "cd")
        val surface = RecordingSurface(10, 2)

        m.draw(surface)

        // Row 0 is selected: background is the selected (inverted) color across the full width.
        surface.top(0, 0)!!.bg shouldBe Color.WHITE
        surface.top(9, 0)!!.bg shouldBe Color.WHITE // padded to full width
        surface.top(0, 1)!!.bg shouldBe Color.BLACK // unselected row
    }
})
