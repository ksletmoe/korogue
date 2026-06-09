package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LogPanelTest : FunSpec({

    /** Reads a row of text out of the inner area (inset by the 1-cell border). */
    fun RecordingSurface.row(
        innerRow: Int,
        innerWidth: Int,
    ): String =
        (0 until innerWidth)
            .map { top(1 + it, 1 + innerRow)?.glyph ?: ' ' }
            .joinToString("")
            .trimEnd()

    test("shows the most recent lines, newest at the bottom of the interior") {
        // 5x4 panel -> inner 3 wide, 2 tall: only the last two lines fit.
        val panel = LogPanel(IntRect(0, 0, 5, 4))
        panel.append("aaa")
        panel.append("bbb")
        panel.append("ccc")
        val surface = RecordingSurface(5, 4)

        panel.draw(surface)

        surface.row(0, 3) shouldBe "bbb" // older of the two visible
        surface.row(1, 3) shouldBe "ccc" // newest, at the bottom
    }

    test("hard-wraps a line wider than the interior") {
        val panel = LogPanel(IntRect(0, 0, 5, 4)) // inner 3 wide, 2 tall
        panel.append("abcdef")
        val surface = RecordingSurface(5, 4)

        panel.draw(surface)

        surface.row(0, 3) shouldBe "abc"
        surface.row(1, 3) shouldBe "def"
    }

    test("PageUp scrolls to older lines; PageDown returns toward the newest") {
        val panel = LogPanel(IntRect(0, 0, 5, 4)) // inner 3 wide, 2 tall
        listOf("l1", "l2", "l3", "l4").forEach(panel::append)

        val atBottom = RecordingSurface(5, 4)
        panel.draw(atBottom)
        atBottom.row(1, 3) shouldBe "l4"

        panel.handleKey(Input.Keys.PAGE_UP) shouldBe true
        val scrolled = RecordingSurface(5, 4)
        panel.draw(scrolled)
        scrolled.row(1, 3) shouldBe "l3" // moved up by ~a page

        panel.handleKey(Input.Keys.PAGE_DOWN)
        val back = RecordingSurface(5, 4)
        panel.draw(back)
        back.row(1, 3) shouldBe "l4" // back to following the tail
    }

    test("caps the buffer at maxLines, evicting the oldest") {
        val panel = LogPanel(IntRect(0, 0, 5, 4), maxLines = 2)
        listOf("a", "b", "c").forEach(panel::append)
        val surface = RecordingSurface(5, 4)

        panel.draw(surface)

        surface.row(0, 3) shouldBe "b" // "a" evicted
        surface.row(1, 3) shouldBe "c"
    }

    test("non-scroll keys are not consumed") {
        val panel = LogPanel(IntRect(0, 0, 5, 4))
        panel.handleKey(Input.Keys.LEFT) shouldBe false
    }
})
