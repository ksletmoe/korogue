package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DialogTest : FunSpec({

    fun menu(onFire: () -> Unit) = Menu(IntRect(0, 0, 8, 1), listOf(MenuItem("OK", onFire)))

    test("draws an opaque background, a border, the title, and its content") {
        val surface = RecordingSurface(12, 6)
        Dialog(IntRect(0, 0, 12, 6), title = "Menu", content = menu {}).draw(surface)

        surface.top(0, 0)!!.glyph shouldBe Cp437.TOP_LEFT // border
        surface.top(2, 0)!!.glyph shouldBe 'M' // title " Menu " -> 'M' at x=2
        surface.top(5, 3)!!.glyph shouldBe ' ' // opaque background fill in the interior
        surface.top(1, 1)!!.glyph shouldBe 'O' // menu content ("OK") inside the inset
    }

    test("forwards keys to its content (Enter activates the menu item)") {
        var fired = false
        val dialog = Dialog(IntRect(0, 0, 12, 6), title = null, content = menu { fired = true })

        dialog.handleKey(Input.Keys.ENTER) shouldBe true
        fired shouldBe true
    }

    test("Escape invokes onCancel when set") {
        var cancelled = false
        val dialog =
            Dialog(IntRect(0, 0, 12, 6), title = null, content = menu {}, onCancel = { cancelled = true })

        dialog.handleKey(Input.Keys.ESCAPE) shouldBe true
        cancelled shouldBe true
    }

    test("Escape is not specially handled without an onCancel (falls to content)") {
        val dialog = Dialog(IntRect(0, 0, 12, 6), title = null, content = menu {})
        // The menu doesn't handle Escape, so the dialog reports it unconsumed.
        dialog.handleKey(Input.Keys.ESCAPE) shouldBe false
    }
})
