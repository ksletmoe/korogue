package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

private class TestWidget(
    override val bounds: IntRect,
    private val glyph: Char = 'x',
    private val consumes: Boolean = false,
) : Widget {
    val keysSeen = mutableListOf<Int>()

    override fun draw(surface: TileSurface) {
        surface.put(0, 0, 0, glyph, Color.WHITE, Color.BLACK)
    }

    override fun handleKey(keycode: Int): Boolean {
        keysSeen.add(keycode)
        return consumes
    }
}

class UiRootTest : FunSpec({

    fun full() = IntRect(0, 0, 10, 10)

    test("renders layers on ascending z-bands so higher layers draw on top") {
        val recording = RecordingSurface(10, 10)
        val ui = UiRoot(recording)
        ui.add(TestWidget(full(), glyph = 'a')) // layer 0
        ui.add(TestWidget(full(), glyph = 'b')) // layer 1

        ui.render()

        // Both drew at (0,0); the top (max z) cell is layer 1's 'b' at z = LAYER_Z_SPAN.
        val top = recording.top(0, 0)!!
        top.glyph shouldBe 'b'
        top.z shouldBe UiRoot.LAYER_Z_SPAN
    }

    test("a widget draws within its own bounds (local origin translated)") {
        val recording = RecordingSurface(20, 20)
        val ui = UiRoot(recording)
        ui.add(TestWidget(IntRect(4, 6, 5, 5), glyph = '@'))

        ui.render()

        val cell = recording.puts.single()
        cell.x shouldBe 4
        cell.y shouldBe 6
    }

    test("layers below the topmost modal render dimmed; the modal does not") {
        val recording = RecordingSurface(10, 10)
        val ui = UiRoot(recording, dimFactor = 0.4f)
        ui.add(TestWidget(full(), glyph = 'a')) // below
        ui.add(TestWidget(full(), glyph = 'd'), modal = true) // modal

        ui.render()

        val below = recording.puts.first { it.glyph == 'a' }
        val modal = recording.puts.first { it.glyph == 'd' }
        below.fg.r shouldBe (0.4f plusOrMinus 1e-4f) // WHITE * 0.4
        modal.fg.r shouldBe 1f // undimmed
    }

    test("without a modal, input goes top-down and the first consumer wins") {
        val ui = UiRoot(RecordingSurface(10, 10))
        val bottom = TestWidget(full(), consumes = false)
        val top = TestWidget(full(), consumes = true)
        ui.add(bottom)
        ui.add(top)

        ui.handleKey(42) shouldBe true
        top.keysSeen shouldContainExactly listOf(42)
        bottom.keysSeen.shouldContainExactly(emptyList()) // top consumed it first
    }

    test("without a modal and nobody consuming, handleKey returns false (falls through to gameplay)") {
        val ui = UiRoot(RecordingSurface(10, 10))
        ui.add(TestWidget(full(), consumes = false))

        ui.handleKey(7) shouldBe false
    }

    test("a modal captures input, swallowing keys it does not handle and shielding layers below") {
        val ui = UiRoot(RecordingSurface(10, 10))
        val below = TestWidget(full(), consumes = true) // would consume, but is shielded
        val modal = TestWidget(full(), consumes = false) // doesn't handle, but swallows
        ui.add(below)
        ui.add(modal, modal = true)

        ui.handleKey(99) shouldBe true // swallowed by the modal
        modal.keysSeen shouldContainExactly listOf(99)
        below.keysSeen.shouldContainExactly(emptyList()) // never reached
    }
})
