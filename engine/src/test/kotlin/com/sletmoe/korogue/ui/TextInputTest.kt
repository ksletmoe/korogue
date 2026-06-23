package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextInputTest : FunSpec({

    fun field(initial: String = ""): Pair<TextInput, MutableList<String>> {
        val submitted = mutableListOf<String>()
        return TextInput(IntRect(0, 0, 20, 1), initial = initial) { submitted.add(it) } to submitted
    }

    test("typed letters, digits, and space accumulate into the value") {
        val (input, _) = field()
        input.handleKey(Input.Keys.A) shouldBe true
        input.handleKey(Input.Keys.B)
        input.handleKey(Input.Keys.SPACE)
        input.handleKey(Input.Keys.NUM_1)
        input.value shouldBe "ab 1"
    }

    test("Backspace deletes the last character; harmless on an empty buffer") {
        val (input, _) = field("foo")
        input.handleKey(Input.Keys.BACKSPACE) shouldBe true
        input.value shouldBe "fo"
        input.handleKey(Input.Keys.BACKSPACE)
        input.handleKey(Input.Keys.BACKSPACE)
        input.handleKey(Input.Keys.BACKSPACE) // already empty
        input.value shouldBe ""
    }

    test("Enter submits the current value") {
        val (input, submitted) = field("healing")
        input.handleKey(Input.Keys.ENTER) shouldBe true
        submitted shouldBe listOf("healing")
    }

    test("keys with no character mapping fall through unconsumed") {
        val (input, _) = field()
        input.handleKey(Input.Keys.LEFT) shouldBe false
        input.handleKey(Input.Keys.F1) shouldBe false
        input.value shouldBe ""
    }

    test("draws the buffer with a trailing caret") {
        val (input, _) = field("hi")
        val surface = RecordingSurface(20, 1)
        input.draw(surface)
        val drawn = (0 until 3).map { surface.top(it, 0)!!.glyph }.joinToString("")
        drawn shouldBe "hi_"
    }
})
