package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HotkeyMenuTest : FunSpec({

    val rows = listOf('a' to "a) mace", 'b' to "b) bow")

    fun menu(
        rows: List<Pair<Char, String>>,
        fg: Color = Color.WHITE,
        bg: Color = Color.BLACK,
    ): Pair<HotkeyMenu, MutableList<Char>> {
        val picked = mutableListOf<Char>()
        return HotkeyMenu(IntRect(0, 0, 10, rows.size), rows, fg = fg, bg = bg, onPick = { picked.add(it) }) to picked
    }

    test("rows render their caller-supplied label, one per line") {
        val (m, _) = menu(rows)
        val surface = RecordingSurface(10, 2)

        m.draw(surface)

        (0 until 7).map { x -> surface.top(x, 0)!!.glyph }.joinToString("") shouldBe "a) mace"
        (0 until 6).map { x -> surface.top(x, 1)!!.glyph }.joinToString("") shouldBe "b) bow"
    }

    test("rows past the bottom of bounds are not drawn") {
        val (m, _) = menu(rows.take(1))
        val surface = RecordingSurface(10, 2)

        m.draw(surface)

        surface.top(0, 1) shouldBe null
    }

    test("a matching key fires onPick with that key and is consumed") {
        val (m, picked) = menu(rows)

        m.handleKey(Input.Keys.B) shouldBe true

        picked shouldBe listOf('b')
    }

    test("a non-matching letter returns false and fires nothing") {
        val (m, picked) = menu(rows)

        m.handleKey(Input.Keys.Z) shouldBe false

        picked shouldBe emptyList()
    }

    test("a non-letter key returns false and fires nothing") {
        val (m, picked) = menu(rows)

        m.handleKey(Input.Keys.ENTER) shouldBe false

        picked shouldBe emptyList()
    }

    test("matching is against a row's key, not its position in the list") {
        val outOfOrder = listOf('c' to "c) ring", 'a' to "a) mace")
        val (m, picked) = menu(outOfOrder)

        m.handleKey(Input.Keys.A) shouldBe true

        picked shouldBe listOf('a')
    }

    test("rows render in the caller's fg/bg colors") {
        val (m, _) = menu(rows, fg = Color.GREEN, bg = Color.BLUE)
        val surface = RecordingSurface(10, 2)

        m.draw(surface)

        surface.top(0, 0)!!.fg shouldBe Color.GREEN
        surface.top(0, 0)!!.bg shouldBe Color.BLUE
    }
})
