package com.sletmoe.krogue.ui

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TileSurfaceTest : FunSpec({

    test("text writes one character per cell to the right") {
        val surface = RecordingSurface(20, 5)
        surface.text(2, 1, z = 3, text = "Hi", fg = Color.WHITE)

        surface.puts.map { Triple(it.x, it.y, it.glyph) } shouldBe
            listOf(Triple(2, 1, 'H'), Triple(3, 1, 'i'))
        surface.puts.all { it.z == 3 } shouldBe true
    }

    test("fill covers every cell on the given layer") {
        val surface = RecordingSurface(4, 3)
        surface.fill(z = 0, glyph = ' ', fg = Color.WHITE, bg = Color.DARK_GRAY)

        surface.puts.size shouldBe 12 // 4 * 3
        surface.top(3, 2)!!.bg shouldBe Color.DARK_GRAY
    }
})
