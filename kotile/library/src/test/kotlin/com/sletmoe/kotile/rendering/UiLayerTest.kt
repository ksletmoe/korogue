package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.KotileCanvas
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the free-layer UI model: [PixelRect] hit math,
 * [UiLayer] visibility, update forwarding, hit-testing, and pointer dispatch.
 * These need no canvas — the test [Widget] records calls without drawing. Actual
 * rendering and draw-order-by-pixels (which need GL) are covered by
 * [com.sletmoe.kotile.RenderingIntegrationTest].
 */
class UiLayerTest : FunSpec({

    /** A [Widget] that records the calls it receives; its [render] never touches the canvas. */
    class RecordingWidget(
        override var bounds: PixelRect,
        override var visible: Boolean = true,
        private val consume: Boolean = false,
        val tag: String = "",
    ) : Widget {
        var updatedMs = 0L
        val downs = mutableListOf<Triple<Float, Float, Int>>()
        val ups = mutableListOf<Triple<Float, Float, Int>>()
        val moves = mutableListOf<Pair<Float, Float>>()

        override fun render(canvas: KotileCanvas) {}

        override fun update(dtMs: Long) {
            updatedMs += dtMs
        }

        override fun onPointerDown(
            px: Float,
            py: Float,
            button: Int,
        ): Boolean {
            downs.add(Triple(px, py, button))
            return consume
        }

        override fun onPointerUp(
            px: Float,
            py: Float,
            button: Int,
        ): Boolean {
            ups.add(Triple(px, py, button))
            return consume
        }

        override fun onPointerMoved(
            px: Float,
            py: Float,
        ): Boolean {
            moves.add(px to py)
            return consume
        }
    }

    // ── PixelRect ──────────────────────────────────────────────────────────

    test("PixelRect contains is left/top inclusive and right/bottom exclusive") {
        val r = PixelRect(10f, 20f, 30f, 40f) // covers x in [10,40), y in [20,60)
        r.right shouldBe 40f
        r.bottom shouldBe 60f
        r.contains(10f, 20f).shouldBeTrue() // top-left corner included
        r.contains(39.9f, 59.9f).shouldBeTrue()
        r.contains(40f, 30f).shouldBeFalse() // right edge excluded
        r.contains(20f, 60f).shouldBeFalse() // bottom edge excluded
        r.contains(9.9f, 30f).shouldBeFalse() // left of the rect
    }

    // ── update forwarding & bookkeeping ──────────────────────────────────────

    test("update forwards the frame delta to every widget, including hidden ones") {
        val w1 = RecordingWidget(PixelRect(0f, 0f, 1f, 1f))
        val w2 = RecordingWidget(PixelRect(0f, 0f, 1f, 1f), visible = false)
        val layer = UiLayer()
        layer.add(w1)
        layer.add(w2)

        layer.update(16)
        layer.update(16)

        w1.updatedMs shouldBe 32L
        w2.updatedMs shouldBe 32L // hidden widgets still animate
    }

    test("add/remove/clear track the widget list") {
        val a = RecordingWidget(PixelRect(0f, 0f, 1f, 1f))
        val b = RecordingWidget(PixelRect(0f, 0f, 1f, 1f))
        val layer = UiLayer()
        layer.add(a)
        layer.add(b)
        layer.widgetCount shouldBe 2
        layer.widgets shouldBe listOf(a, b) // insertion order (a bottom, b top)
        layer.remove(a).shouldBeTrue()
        layer.remove(a).shouldBeFalse()
        layer.widgets shouldBe listOf(b)
        layer.clear()
        layer.widgetCount shouldBe 0
    }

    // ── Hit-testing ──────────────────────────────────────────────────────────

    test("widgetAt returns the topmost widget covering the point") {
        val bottom = RecordingWidget(PixelRect(0f, 0f, 100f, 100f), tag = "bottom")
        val top = RecordingWidget(PixelRect(40f, 40f, 20f, 20f), tag = "top") // overlaps bottom
        val layer = UiLayer()
        layer.add(bottom)
        layer.add(top)

        // Inside the overlap: the later-added (topmost) widget wins.
        (layer.widgetAt(50f, 50f) as RecordingWidget).tag shouldBe "top"
        // Covered only by the bottom widget.
        (layer.widgetAt(10f, 10f) as RecordingWidget).tag shouldBe "bottom"
        // Outside every widget.
        layer.widgetAt(200f, 200f).shouldBeNull()
    }

    test("widgetAt skips hidden widgets") {
        val hidden = RecordingWidget(PixelRect(0f, 0f, 100f, 100f), visible = false, tag = "hidden")
        val shown = RecordingWidget(PixelRect(0f, 0f, 50f, 50f), tag = "shown")
        val layer = UiLayer()
        layer.add(shown)
        layer.add(hidden) // hidden is topmost but invisible

        (layer.widgetAt(10f, 10f) as RecordingWidget).tag shouldBe "shown"
        layer.widgetAt(80f, 80f).shouldBeNull() // only the hidden widget covers this
    }

    // ── Pointer dispatch ─────────────────────────────────────────────────────

    test("a consuming top widget stops the press from reaching widgets below") {
        val bottom = RecordingWidget(PixelRect(0f, 0f, 100f, 100f), tag = "bottom")
        val top = RecordingWidget(PixelRect(40f, 40f, 20f, 20f), consume = true, tag = "top")
        val layer = UiLayer()
        layer.add(bottom)
        layer.add(top)

        layer.onPointerDown(50f, 50f, 0).shouldBeTrue()

        top.downs shouldBe listOf(Triple(50f, 50f, 0))
        bottom.downs.isEmpty().shouldBeTrue() // top consumed it -> no fall-through
    }

    test("a non-consuming top widget lets the press fall through to the widget below") {
        val bottom = RecordingWidget(PixelRect(0f, 0f, 100f, 100f), tag = "bottom")
        val top = RecordingWidget(PixelRect(40f, 40f, 20f, 20f), consume = false, tag = "top")
        val layer = UiLayer()
        layer.add(bottom)
        layer.add(top)

        // Over the overlap: top gets it first (returns false), then bottom.
        layer.onPointerDown(50f, 50f, 0).shouldBeFalse() // neither consumed
        top.downs shouldBe listOf(Triple(50f, 50f, 0))
        bottom.downs shouldBe listOf(Triple(50f, 50f, 0))
    }

    test("dispatch skips widgets not covering the point and hidden widgets") {
        val hidden = RecordingWidget(PixelRect(0f, 0f, 100f, 100f), visible = false, tag = "hidden")
        val elsewhere = RecordingWidget(PixelRect(80f, 80f, 10f, 10f), tag = "elsewhere")
        val hit = RecordingWidget(PixelRect(0f, 0f, 20f, 20f), tag = "hit")
        val layer = UiLayer()
        layer.add(elsewhere)
        layer.add(hit)
        layer.add(hidden)

        layer.onPointerDown(5f, 5f, 0)

        hit.downs shouldBe listOf(Triple(5f, 5f, 0))
        hidden.downs.isEmpty().shouldBeTrue() // invisible
        elsewhere.downs.isEmpty().shouldBeTrue() // does not cover the point
    }

    test("pointer dispatch returns the widget's consumed flag; false when nothing is hit") {
        val consuming = RecordingWidget(PixelRect(0f, 0f, 10f, 10f), consume = true)
        val layer = UiLayer()
        layer.add(consuming)

        layer.onPointerDown(5f, 5f, 1).shouldBeTrue() // widget consumed it
        layer.onPointerUp(5f, 5f, 1).shouldBeTrue()
        layer.onPointerMoved(5f, 5f).shouldBeTrue()
        layer.onPointerDown(500f, 500f, 1).shouldBeFalse() // no widget under the point
    }

    test("a non-consuming widget reports false but still receives the event") {
        val passthrough = RecordingWidget(PixelRect(0f, 0f, 10f, 10f), consume = false)
        val layer = UiLayer()
        layer.add(passthrough)

        layer.onPointerMoved(5f, 5f).shouldBeFalse()
        passthrough.moves shouldBe listOf(5f to 5f)
    }
})
