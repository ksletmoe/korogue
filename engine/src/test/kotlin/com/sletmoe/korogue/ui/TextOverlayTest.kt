package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextOverlayTest : FunSpec({

    /** The full grid of [width] x [height], top to bottom, as rendered glyphs (' ' where nothing drawn). */
    fun TextOverlay.render(width: Int, height: Int): List<String> {
        val surface = RecordingSurface(width, height)
        draw(surface)
        return (0 until height).map { y ->
            (0 until width).map { x -> surface.top(x, y)?.glyph ?: ' ' }.joinToString("")
        }
    }

    test("clears the whole surface to the background before painting") {
        val surface = RecordingSurface(6, 4)
        TextOverlay(IntRect(0, 0, 6, 4), listOf("hi"), fg = Color.WHITE, bg = Color.BLUE).draw(surface)

        surface.top(5, 3)!!.glyph shouldBe ' ' // untouched corner still got the clear
        surface.top(5, 3)!!.bg shouldBe Color.BLUE
    }

    test("centers a single line vertically: top = (height - lines.size) / 2") {
        val rows = TextOverlay(IntRect(0, 0, 5, 5), listOf("hi")).render(5, 5)
        rows shouldBe listOf("     ", "     ", "hi   ", "     ", "     ") // (5-1)/2 = 2
    }

    test("odd remainder leans up, matching integer-division rounding") {
        val rows = TextOverlay(IntRect(0, 0, 3, 4), listOf("a", "b", "c")).render(3, 4)
        rows shouldBe listOf("a  ", "b  ", "c  ", "   ") // (4-3)/2 = 0, extra row falls below
    }

    test("lines exceeding the surface height are cropped, not overflowed") {
        val rows = TextOverlay(IntRect(0, 0, 3, 2), listOf("a", "b", "c", "d")).render(3, 2)
        rows shouldBe listOf("a  ", "b  ") // top clamps to 0, tail is dropped
    }

    test("truncates over-long lines to the surface width") {
        val rows = TextOverlay(IntRect(0, 0, 4, 1), listOf("abcdefgh")).render(4, 1)
        rows shouldBe listOf("abcd")
    }

    test("left-aligned by default (centerHorizontally = false)") {
        val rows = TextOverlay(IntRect(0, 0, 6, 1), listOf("hi")).render(6, 1)
        rows shouldBe listOf("hi    ")
    }

    test("centerHorizontally centers each line: x = (width - line.length) / 2") {
        val rows = TextOverlay(IntRect(0, 0, 6, 1), listOf("hi"), centerHorizontally = true).render(6, 1)
        rows shouldBe listOf("  hi  ") // (6-2)/2 = 2
    }

    test("centerHorizontally handles lines of different lengths independently") {
        val rows = TextOverlay(IntRect(0, 0, 7, 2), listOf("a", "abc"), centerHorizontally = true).render(7, 2)
        rows shouldBe listOf("   a   ", "  abc  ")
    }

    test("with no dismissKeys/onDismiss, the overlay is fully display-only") {
        TextOverlay(IntRect(0, 0, 5, 5), listOf("x")).handleKey(Input.Keys.SPACE) shouldBe false
    }

    test("a matching dismiss key invokes onDismiss and is consumed") {
        var dismissed = 0
        val overlay =
            TextOverlay(
                IntRect(0, 0, 5, 5),
                listOf("x"),
                dismissKeys = setOf(Input.Keys.SPACE, Input.Keys.ESCAPE),
                onDismiss = { dismissed++ },
            )

        overlay.handleKey(Input.Keys.A) shouldBe false
        dismissed shouldBe 0
        overlay.handleKey(Input.Keys.ESCAPE) shouldBe true
        dismissed shouldBe 1
    }

    test("dismissKeys without onDismiss stay unconsumed (nothing to invoke)") {
        TextOverlay(IntRect(0, 0, 5, 5), listOf("x"), dismissKeys = setOf(Input.Keys.SPACE))
            .handleKey(Input.Keys.SPACE) shouldBe false
    }
})
