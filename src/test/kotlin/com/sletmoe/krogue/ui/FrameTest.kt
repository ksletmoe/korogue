package com.sletmoe.krogue.ui

import com.sletmoe.krogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class FrameTest : FunSpec({

    test("draws CP437 corners and edges around its bounds") {
        val surface = RecordingSurface(6, 4)
        Frame(IntRect(0, 0, 6, 4)).draw(surface)

        surface.top(0, 0)!!.glyph shouldBe Cp437.TOP_LEFT
        surface.top(5, 0)!!.glyph shouldBe Cp437.TOP_RIGHT
        surface.top(0, 3)!!.glyph shouldBe Cp437.BOTTOM_LEFT
        surface.top(5, 3)!!.glyph shouldBe Cp437.BOTTOM_RIGHT
        surface.top(2, 0)!!.glyph shouldBe Cp437.HORIZONTAL // top edge
        surface.top(0, 1)!!.glyph shouldBe Cp437.VERTICAL // left edge
        surface.top(2, 2).let { it == null || it.glyph == ' ' } shouldBe true // interior untouched
    }

    test("writes the title onto the top edge") {
        val surface = RecordingSurface(10, 3)
        Frame(IntRect(0, 0, 10, 3), title = "Hi").draw(surface)

        // " Hi " starts at x=1, so 'H' lands at x=2.
        surface.top(2, 0)!!.glyph shouldBe 'H'
        surface.top(3, 0)!!.glyph shouldBe 'i'
    }

    test("draws nothing when too small for a border") {
        val surface = RecordingSurface(1, 5)
        Frame(IntRect(0, 0, 1, 5)).draw(surface)
        surface.puts.shouldBeEmpty()
    }
})
