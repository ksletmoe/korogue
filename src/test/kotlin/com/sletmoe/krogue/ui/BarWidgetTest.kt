package com.sletmoe.krogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BarWidgetTest : FunSpec({

    val filled = Color.FOREST
    val empty = Color.MAROON

    fun bar(
        width: Int,
        value: BarValue,
    ) = BarWidget(IntRect(0, 0, width, 1), "HP", filled, empty) { value }

    test("renders the label, a proportionally filled bar, and the cur/max suffix") {
        val surface = RecordingSurface(12, 1)
        bar(12, BarValue(5, 10)).draw(surface) // prefix "HP " (3), suffix " 5/10" (5), bar width 4

        surface.top(0, 0)!!.glyph shouldBe 'H' // label
        // bar occupies x = 3..6; half full -> 2 filled, 2 empty
        surface.top(3, 0)!!.glyph shouldBe Cp437.FULL_BLOCK
        surface.top(3, 0)!!.fg shouldBe filled
        surface.top(4, 0)!!.fg shouldBe filled
        surface.top(5, 0)!!.fg shouldBe empty
        surface.top(6, 0)!!.fg shouldBe empty
        surface.top(8, 0)!!.glyph shouldBe '5' // suffix " 5/10" starts at x=7
    }

    test("a full bar has no empty cells; an empty bar has no filled cells") {
        val fullSurface = RecordingSurface(12, 1)
        bar(12, BarValue(10, 10)).draw(fullSurface)
        fullSurface.puts.any { it.glyph == Cp437.FULL_BLOCK && it.fg == filled } shouldBe true
        fullSurface.puts.none { it.glyph == Cp437.FULL_BLOCK && it.fg == empty } shouldBe true

        val emptySurface = RecordingSurface(12, 1)
        bar(12, BarValue(0, 10)).draw(emptySurface)
        emptySurface.puts.none { it.glyph == Cp437.FULL_BLOCK && it.fg == filled } shouldBe true
    }

    test("a zero max renders empty rather than dividing by zero") {
        val surface = RecordingSurface(12, 1)
        bar(12, BarValue(0, 0)).draw(surface)
        surface.top(3, 0)!!.fg shouldBe empty
    }
})
