package com.sletmoe.kotile.input

import com.sletmoe.kotile.rendering.GridLayout
import com.sletmoe.kotile.rendering.IntegerScale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [KotileInputProcessor].
 *
 * These tests are pure — no libGDX GL context is required. The processor is
 * constructed with fixed lambda values so tile dimensions and grid size can be
 * controlled precisely.
 */
class KotileInputProcessorTest : FunSpec({

    // Helper: a processor over a reflow layout of 8×4 tiles at 10×10px (offset 0),
    // so pixel (x, y) maps to tile (x/10, y/10) — the fixed mapping the dispatch
    // tests below assume.
    fun processor() =
        KotileInputProcessor(
            layout = { GridLayout.forReflow(80, 40, 10, 10) },
        )

    // ── Layout-based constructor (scaling / letterboxing) ─────────────────

    test("layout constructor maps a click inside a scaled, letterboxed grid") {
        // 80×24 grid, 10px native, 2x scale, centered at (50, 20): a click at
        // grid cell (2, 3) lands at pixel (50 + 2*20 + 5, 20 + 3*20 + 5).
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        val proc = KotileInputProcessor(layout = { layout })
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(Triple(tileX, tileY, button))
                }
            },
        )
        proc.touchDown(95, 85, 0, 1)
        clicks shouldBe listOf(Triple(2, 3, 1))
    }

    test("layout constructor drops a click in the letterbox margin") {
        val layout = GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale)
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        val proc = KotileInputProcessor(layout = { layout })
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(Triple(tileX, tileY, button))
                }
            },
        )
        proc.touchDown(10, 10, 0, 0) // top-left letterbox bar
        clicks.shouldBeEmpty()
    }

    // ── Tile-click dispatch ───────────────────────────────────────────────

    test("touchDown at top-left pixel fires onTileClicked(0, 0)") {
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(Triple(tileX, tileY, button))
                }
            },
        )
        proc.touchDown(0, 0, 0, 0)
        clicks shouldBe listOf(Triple(0, 0, 0))
    }

    test("touchDown at mid-grid pixel fires correct tile coordinates") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(tileX to tileY)
                }
            },
        )
        // pixel (35, 25) → tile (3, 2) with 10×10 tiles
        proc.touchDown(35, 25, 0, 1)
        clicks shouldBe listOf(3 to 2)
    }

    test("touchDown outside the grid fires no event") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(tileX to tileY)
                }
            },
        )
        // 8 × 10px = 80px; pixel x=80 is out of bounds
        proc.touchDown(80, 0, 0, 0)
        clicks.shouldBeEmpty()
    }

    test("touchDown with negative coordinates fires no event") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(tileX to tileY)
                }
            },
        )
        proc.touchDown(-1, 5, 0, 0)
        clicks.shouldBeEmpty()
    }

    // ── Mouse hover dispatch ──────────────────────────────────────────────

    test("mouseMoved inside grid fires onTileHovered with correct tile") {
        val hovers = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileHovered(
                    tileX: Int,
                    tileY: Int,
                ) {
                    hovers.add(tileX to tileY)
                }
            },
        )
        // pixel (10, 20) → tile (1, 2)
        proc.mouseMoved(10, 20)
        hovers shouldBe listOf(1 to 2)
    }

    test("mouseMoved outside grid fires no onTileHovered") {
        val hovers = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileHovered(
                    tileX: Int,
                    tileY: Int,
                ) {
                    hovers.add(tileX to tileY)
                }
            },
        )
        proc.mouseMoved(100, 100) // far outside 8×4 grid of 10×10 tiles
        hovers.shouldBeEmpty()
    }

    // ── Key event dispatch ────────────────────────────────────────────────

    test("keyDown propagates keycode to listener") {
        val codes = mutableListOf<Int>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onKeyDown(keycode: Int) {
                    codes.add(keycode)
                }
            },
        )
        proc.keyDown(42)
        codes shouldBe listOf(42)
    }

    test("keyUp propagates keycode to listener") {
        val codes = mutableListOf<Int>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onKeyUp(keycode: Int) {
                    codes.add(keycode)
                }
            },
        )
        proc.keyUp(7)
        codes shouldBe listOf(7)
    }

    test("keyTyped propagates character to listener") {
        val chars = mutableListOf<Char>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onKeyTyped(character: Char) {
                    chars.add(character)
                }
            },
        )
        proc.keyTyped('A')
        chars shouldBe listOf('A')
    }

    // ── Scroll dispatch ───────────────────────────────────────────────────

    test("scrolled propagates amounts to listener") {
        val scrolls = mutableListOf<Pair<Float, Float>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onScrolled(
                    amountX: Float,
                    amountY: Float,
                ) {
                    scrolls.add(amountX to amountY)
                }
            },
        )
        proc.scrolled(1.0f, -2.5f)
        scrolls shouldBe listOf(1.0f to -2.5f)
    }

    // ── Multiple listeners ────────────────────────────────────────────────

    test("multiple listeners all receive the same tile-click event") {
        val results = mutableListOf<String>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    results.add("A:$tileX,$tileY")
                }
            },
        )
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    results.add("B:$tileX,$tileY")
                }
            },
        )
        proc.touchDown(0, 0, 0, 0)
        results shouldBe listOf("A:0,0", "B:0,0")
    }

    // ── removeListener ────────────────────────────────────────────────────

    test("removed listener no longer receives events") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val listener =
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(tileX to tileY)
                }
            }
        val proc = processor()
        proc.addListener(listener)
        proc.touchDown(0, 0, 0, 0)
        proc.removeListener(listener)
        proc.touchDown(10, 0, 0, 0)
        // Only the first click before removal should be recorded
        clicks shouldBe listOf(0 to 0)
    }

    // ── Lazy layout provider ──────────────────────────────────────────────

    test("processor reads the current layout on each event") {
        // 10px tiles → 8×4 grid; a simulated resize to 20px tiles → 4×2 grid.
        var layout = GridLayout.forReflow(80, 40, 10, 10)
        val clicks = mutableListOf<Pair<Int, Int>>()

        val proc = KotileInputProcessor(layout = { layout })
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    clicks.add(tileX to tileY)
                }
            },
        )

        // With 10px tiles: pixel 35 → tile 3
        proc.touchDown(35, 0, 0, 0)

        // Simulate resize: tiles become 20px wide, grid shrinks to 4 cols
        layout = GridLayout.forReflow(80, 40, 20, 20)

        // With 20px tiles: pixel 35 → tile 1
        proc.touchDown(35, 0, 0, 0)

        clicks shouldBe listOf(3 to 0, 1 to 0)
    }

    // ── Drag events ───────────────────────────────────────────────────────

    test("touchDragged inside grid fires onTileDragged with -1 button") {
        val drags = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileDragged(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    drags.add(Triple(tileX, tileY, button))
                }
            },
        )
        // pixel (15, 5) → tile (1, 0) with 10×10 tiles; button = -1 (libGDX limitation)
        proc.touchDragged(15, 5, 0)
        drags shouldBe listOf(Triple(1, 0, -1))
    }

    test("touchDragged outside grid fires no onTileDragged") {
        val drags = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onTileDragged(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    drags.add(Triple(tileX, tileY, button))
                }
            },
        )
        proc.touchDragged(200, 200, 0) // far outside grid
        drags.shouldBeEmpty()
    }

    // ── Free (pixel-space) pointer events ─────────────────────────────────

    // A processor over a fixed grid with a non-zero letterbox offset, so pixel
    // events must subtract the centering offset (not just be raw window pixels).
    // 80×24 grid, 10px native, 2x scale, centered at (50, 20): window pixel
    // (px, py) maps to content pixel (px - 50, py - 20).
    fun offsetProcessor() =
        KotileInputProcessor(
            layout = { GridLayout.forFixedGrid(1700, 520, 80, 24, 10, 10, IntegerScale) },
        )

    test("touchDown fires onPointerDown in content-pixel space alongside onTileClicked") {
        val downs = mutableListOf<Triple<Float, Float, Int>>()
        val tiles = mutableListOf<Pair<Int, Int>>()
        val proc = offsetProcessor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onPointerDown(
                    px: Float,
                    py: Float,
                    button: Int,
                ) {
                    downs.add(Triple(px, py, button))
                }

                override fun onTileClicked(
                    tileX: Int,
                    tileY: Int,
                    button: Int,
                ) {
                    tiles.add(tileX to tileY)
                }
            },
        )
        proc.touchDown(95, 85, 0, 1) // window (95,85) → content (45,65), tile (2,3)
        downs shouldBe listOf(Triple(45f, 65f, 1))
        tiles shouldBe listOf(2 to 3) // tile event still fires unchanged
    }

    test("touchUp fires onPointerUp in content-pixel space") {
        val ups = mutableListOf<Triple<Float, Float, Int>>()
        val proc = offsetProcessor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onPointerUp(
                    px: Float,
                    py: Float,
                    button: Int,
                ) {
                    ups.add(Triple(px, py, button))
                }
            },
        )
        proc.touchUp(50, 20, 0, 0) // content (0,0)
        ups shouldBe listOf(Triple(0f, 0f, 0))
    }

    test("mouseMoved fires onPointerMoved in content-pixel space") {
        val moves = mutableListOf<Pair<Float, Float>>()
        val proc = offsetProcessor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onPointerMoved(
                    px: Float,
                    py: Float,
                ) {
                    moves.add(px to py)
                }
            },
        )
        proc.mouseMoved(70, 40) // content (20,20)
        moves shouldBe listOf(20f to 20f)
    }

    test("touchDragged fires onPointerDragged in content-pixel space") {
        val drags = mutableListOf<Pair<Float, Float>>()
        val proc = offsetProcessor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onPointerDragged(
                    px: Float,
                    py: Float,
                ) {
                    drags.add(px to py)
                }
            },
        )
        proc.touchDragged(60, 30, 0) // content (10,10)
        drags shouldBe listOf(10f to 10f)
    }

    test("pointer events in the letterbox margin are dropped") {
        val downs = mutableListOf<Pair<Float, Float>>()
        val moves = mutableListOf<Pair<Float, Float>>()
        val proc = offsetProcessor()
        proc.addListener(
            object : KotileInputAdapter() {
                override fun onPointerDown(
                    px: Float,
                    py: Float,
                    button: Int,
                ) {
                    downs.add(px to py)
                }

                override fun onPointerMoved(
                    px: Float,
                    py: Float,
                ) {
                    moves.add(px to py)
                }
            },
        )
        proc.touchDown(10, 10, 0, 0) // top-left letterbox bar
        proc.mouseMoved(10, 10)
        downs.shouldBeEmpty()
        moves.shouldBeEmpty()
    }

    test("pointer events default to no-ops for grid/text-only listeners") {
        // A listener that overrides none of the pointer methods must not throw
        // when the processor delivers them.
        val proc = offsetProcessor()
        proc.addListener(object : KotileInputAdapter() {})
        proc.touchDown(60, 30, 0, 0)
        proc.touchUp(60, 30, 0, 0)
        proc.mouseMoved(60, 30)
        proc.touchDragged(60, 30, 0)
        // No assertion needed: reaching here without an exception is the check.
    }
})
