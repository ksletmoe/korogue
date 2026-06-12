package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LabelTest : FunSpec({

    test("draws the text on its top row, one char per cell") {
        val surface = RecordingSurface(10, 3)
        Label(IntRect(0, 0, 10, 3), fg = Color.WHITE) { "Hi" }.draw(surface)

        surface.top(0, 0)!!.glyph shouldBe 'H'
        surface.top(1, 0)!!.glyph shouldBe 'i'
        surface.top(2, 0) shouldBe null // nothing past the text
    }

    test("truncates text to the surface width") {
        val surface = RecordingSurface(3, 1)
        Label(IntRect(0, 0, 3, 1)) { "abcdef" }.draw(surface)

        surface.puts.map { it.glyph }.joinToString("") shouldBe "abc"
    }

    test("re-reads the provider each draw, so it reflects live state") {
        var hp = 12
        val label = Label(IntRect(0, 0, 6, 1)) { "Hp:$hp" }

        val first = RecordingSurface(6, 1)
        label.draw(first)
        first.puts.map { it.glyph }.joinToString("") shouldBe "Hp:12"

        hp = 9
        val second = RecordingSurface(6, 1)
        label.draw(second)
        second.puts.map { it.glyph }.joinToString("") shouldBe "Hp:9"
    }
})
