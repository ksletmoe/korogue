package com.sletmoe.kotile.rendering

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the display-scaling and centering math introduced by
 * krogue-n64. These run everywhere; the pixel-level GL integration tests
 * ([com.sletmoe.kotile.ViewportIntegrationTest]) only run when a GL context is
 * available.
 */
class GridLayoutTest : FunSpec({

    // ── Scale policies ──────────────────────────────────────────────────────

    test("IntegerScale floors to a whole factor") {
        // 80x24 grid at 10px native = 800x240. A 1700x520 window fits 2.1x wide,
        // 2.16x tall -> floor to 2x.
        IntegerScale.scale(1700, 520, 800, 240) shouldBe 2f
    }

    test("IntegerScale never returns below 1x even when the grid overflows") {
        IntegerScale.scale(400, 120, 800, 240) shouldBe 1f
    }

    test("IntegerScale is limited by the tighter axis") {
        // Wide but short window: height only allows 1x.
        IntegerScale.scale(5000, 300, 800, 240) shouldBe 1f
    }

    test("FitScale returns the fractional factor of the tighter axis") {
        // 900/800 = 1.125, 360/240 = 1.5 -> min = 1.125
        FitScale.scale(900, 360, 800, 240) shouldBe (1.125f plusOrMinus 1e-4f)
    }

    test("FitScale shrinks below 1x when the grid is larger than the window") {
        // 400/800 = 0.5, 240/240 = 1.0 -> 0.5
        FitScale.scale(400, 240, 800, 240) shouldBe (0.5f plusOrMinus 1e-4f)
    }

    // ── Reflow layout ───────────────────────────────────────────────────────

    test("reflow with an exact multiple has no letterbox margin") {
        val layout = GridLayout.forReflow(800, 400, 10, 10)
        layout.columns shouldBe 80
        layout.rows shouldBe 40
        layout.tileWidthPx shouldBe 10f
        layout.offsetXPx shouldBe 0f
        layout.offsetYPx shouldBe 0f
    }

    test("reflow centers the sub-tile remainder instead of anchoring top-left") {
        // 805x406 at 10px -> 80x40 cells (800x400 content); 5px / 6px remainder
        // split into centered margins.
        val layout = GridLayout.forReflow(805, 406, 10, 10)
        layout.columns shouldBe 80
        layout.rows shouldBe 40
        layout.offsetXPx shouldBe (2.5f plusOrMinus 1e-4f)
        layout.offsetYPx shouldBe (3f plusOrMinus 1e-4f)
    }

    test("reflow keeps native tile size regardless of window size") {
        val layout = GridLayout.forReflow(1920, 1080, 12, 12)
        layout.tileWidthPx shouldBe 12f
        layout.tileHeightPx shouldBe 12f
        layout.columns shouldBe 160
        layout.rows shouldBe 90
    }

    // ── Fixed-grid layout ───────────────────────────────────────────────────

    test("fixed grid scales up by an integer factor and letterboxes") {
        // 80x24 grid, 10px native (800x240). 1700x520 window -> 2x (1600x480),
        // centered: x margin (1700-1600)/2 = 50, y margin (520-480)/2 = 20.
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        layout.columns shouldBe 80
        layout.rows shouldBe 24
        layout.tileWidthPx shouldBe 20f
        layout.tileHeightPx shouldBe 20f
        layout.offsetXPx shouldBe (50f plusOrMinus 1e-4f)
        layout.offsetYPx shouldBe (20f plusOrMinus 1e-4f)
        layout.contentWidthPx shouldBe (1600f plusOrMinus 1e-4f)
        layout.contentHeightPx shouldBe (480f plusOrMinus 1e-4f)
    }

    test("fixed grid at an exact native fit is unscaled with no margin") {
        val layout = GridLayout.forFixedGrid(800, 240, 80, 24, 10, 10, IntegerScale)
        layout.tileWidthPx shouldBe 10f
        layout.offsetXPx shouldBe 0f
        layout.offsetYPx shouldBe 0f
    }

    test("fixed grid defaults to IntegerScale") {
        val a = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10)
        val b = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        a shouldBe b
    }

    test("fixed grid with FitScale fills the tighter axis exactly") {
        // 900x360 window, 800x240 native. FitScale = min(1.125, 1.5) = 1.125.
        val layout = GridLayout.forFixedGrid(900, 360, 80, 24, 10, 10, FitScale)
        layout.tileWidthPx shouldBe (11.25f plusOrMinus 1e-4f)
        // width fills exactly: 80 * 11.25 = 900 -> no horizontal margin.
        layout.offsetXPx shouldBe (0f plusOrMinus 1e-4f)
        // height is letterboxed: 24 * 11.25 = 270, margin (360-270)/2 = 45.
        layout.offsetYPx shouldBe (45f plusOrMinus 1e-4f)
    }

    // ── Pixel -> tile hit-testing ───────────────────────────────────────────

    test("tileAt maps a pixel inside a scaled, letterboxed grid to the right cell") {
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        // Grid origin at (50, 20), 20px tiles. Pixel (50, 20) is cell (0, 0).
        layout.tileAt(50f, 20f) shouldBe (0 to 0)
        // Pixel (50 + 2*20 + 5, 20 + 3*20 + 5) is cell (2, 3).
        layout.tileAt(95f, 85f) shouldBe (2 to 3)
    }

    test("tileAt returns null in the letterbox margin") {
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        layout.tileAt(10f, 10f).shouldBeNull() // top-left margin
        layout.tileAt(49f, 20f).shouldBeNull() // just left of the grid
    }

    test("tileAt returns null past the grid's right/bottom edge") {
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        // Right edge of content is 50 + 1600 = 1650; anything >= is out.
        layout.tileAt(1650f, 100f).shouldBeNull()
    }

    // ── tileAt on a reflow layout (offset 0) — the plain pixel→tile mapping ──

    test("tileAt maps interior pixels on an exact-fit reflow grid") {
        // 8×4 grid at 10px, no margin. Mirrors the classic pixel/tile division.
        val layout = GridLayout.forReflow(80, 40, 10, 10)
        layout.tileAt(0f, 0f) shouldBe (0 to 0)
        layout.tileAt(9f, 9f) shouldBe (0 to 0) // still inside the first cell
        layout.tileAt(35f, 25f) shouldBe (3 to 2)
        layout.tileAt(79f, 39f) shouldBe (7 to 3) // last valid pixel
    }

    test("tileAt returns null past the edge and for negatives on a reflow grid") {
        val layout = GridLayout.forReflow(80, 40, 10, 10)
        layout.tileAt(80f, 0f).shouldBeNull() // one past the right edge
        layout.tileAt(0f, 40f).shouldBeNull() // one past the bottom edge
        layout.tileAt(-1f, 5f).shouldBeNull()
        layout.tileAt(5f, -1f).shouldBeNull()
    }

    test("tileAt handles non-square tiles") {
        // 16×24 tiles, 80×120 window → 5×5 grid, offset 0.
        val layout = GridLayout.forReflow(80, 120, 16, 24)
        layout.columns shouldBe 5
        layout.rows shouldBe 5
        layout.tileAt(32f, 48f) shouldBe (2 to 2)
        layout.tileAt(79f, 119f) shouldBe (4 to 4)
    }

    test("tileAt returns null in a centered reflow remainder margin") {
        // 85×45 window at 10px → 8×4 cells (80×40 content), centered: offset (2.5, 2.5).
        val layout = GridLayout.forReflow(85, 45, 10, 10)
        layout.tileAt(1f, 1f).shouldBeNull()     // top-left margin
        layout.tileAt(2.5f, 2.5f) shouldBe (0 to 0) // first cell begins after the margin
        layout.tileAt(82.5f, 20f).shouldBeNull() // right margin (content ends at x=82.5)
    }
})
