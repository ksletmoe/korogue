package com.sletmoe.kotile.input

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

    // Helper to build a processor with 10×10 tiles and an 8×4 grid.
    fun processor(
        tileW: Int = 10, tileH: Int = 10,
        gridW: Int = 8, gridH: Int = 4,
    ) = KotileInputProcessor(
        tileWidthPx = { tileW },
        tileHeightPx = { tileH },
        gridWidth = { gridW },
        gridHeight = { gridH },
    )

    // ── Tile-click dispatch ───────────────────────────────────────────────

    test("touchDown at top-left pixel fires onTileClicked(0, 0)") {
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                clicks.add(Triple(tileX, tileY, button))
            }
        })
        proc.touchDown(0, 0, 0, 0)
        clicks shouldBe listOf(Triple(0, 0, 0))
    }

    test("touchDown at mid-grid pixel fires correct tile coordinates") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                clicks.add(tileX to tileY)
            }
        })
        // pixel (35, 25) → tile (3, 2) with 10×10 tiles
        proc.touchDown(35, 25, 0, 1)
        clicks shouldBe listOf(3 to 2)
    }

    test("touchDown outside the grid fires no event") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                clicks.add(tileX to tileY)
            }
        })
        // 8 × 10px = 80px; pixel x=80 is out of bounds
        proc.touchDown(80, 0, 0, 0)
        clicks.shouldBeEmpty()
    }

    test("touchDown with negative coordinates fires no event") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                clicks.add(tileX to tileY)
            }
        })
        proc.touchDown(-1, 5, 0, 0)
        clicks.shouldBeEmpty()
    }

    // ── Mouse hover dispatch ──────────────────────────────────────────────

    test("mouseMoved inside grid fires onTileHovered with correct tile") {
        val hovers = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileHovered(tileX: Int, tileY: Int) {
                hovers.add(tileX to tileY)
            }
        })
        // pixel (10, 20) → tile (1, 2)
        proc.mouseMoved(10, 20)
        hovers shouldBe listOf(1 to 2)
    }

    test("mouseMoved outside grid fires no onTileHovered") {
        val hovers = mutableListOf<Pair<Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileHovered(tileX: Int, tileY: Int) {
                hovers.add(tileX to tileY)
            }
        })
        proc.mouseMoved(100, 100) // far outside 8×4 grid of 10×10 tiles
        hovers.shouldBeEmpty()
    }

    // ── Key event dispatch ────────────────────────────────────────────────

    test("keyDown propagates keycode to listener") {
        val codes = mutableListOf<Int>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onKeyDown(keycode: Int) {
                codes.add(keycode)
            }
        })
        proc.keyDown(42)
        codes shouldBe listOf(42)
    }

    test("keyUp propagates keycode to listener") {
        val codes = mutableListOf<Int>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onKeyUp(keycode: Int) {
                codes.add(keycode)
            }
        })
        proc.keyUp(7)
        codes shouldBe listOf(7)
    }

    test("keyTyped propagates character to listener") {
        val chars = mutableListOf<Char>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onKeyTyped(character: Char) {
                chars.add(character)
            }
        })
        proc.keyTyped('A')
        chars shouldBe listOf('A')
    }

    // ── Scroll dispatch ───────────────────────────────────────────────────

    test("scrolled propagates amounts to listener") {
        val scrolls = mutableListOf<Pair<Float, Float>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onScrolled(amountX: Float, amountY: Float) {
                scrolls.add(amountX to amountY)
            }
        })
        proc.scrolled(1.0f, -2.5f)
        scrolls shouldBe listOf(1.0f to -2.5f)
    }

    // ── Multiple listeners ────────────────────────────────────────────────

    test("multiple listeners all receive the same tile-click event") {
        val results = mutableListOf<String>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                results.add("A:$tileX,$tileY")
            }
        })
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                results.add("B:$tileX,$tileY")
            }
        })
        proc.touchDown(0, 0, 0, 0)
        results shouldBe listOf("A:0,0", "B:0,0")
    }

    // ── removeListener ────────────────────────────────────────────────────

    test("removed listener no longer receives events") {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val listener = object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
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

    // ── Lazy tile-dimension lambdas ───────────────────────────────────────

    test("processor reads current tile dimensions on each event") {
        var currentTileW = 10
        var currentGridW = 8
        val clicks = mutableListOf<Pair<Int, Int>>()

        val proc = KotileInputProcessor(
            tileWidthPx = { currentTileW },
            tileHeightPx = { 10 },
            gridWidth = { currentGridW },
            gridHeight = { 4 },
        )
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileClicked(tileX: Int, tileY: Int, button: Int) {
                clicks.add(tileX to tileY)
            }
        })

        // With 10px tiles: pixel 35 → tile 3
        proc.touchDown(35, 0, 0, 0)

        // Simulate resize: tiles become 20px wide, grid shrinks to 4 cols
        currentTileW = 20
        currentGridW = 4

        // With 20px tiles: pixel 35 → tile 1
        proc.touchDown(35, 0, 0, 0)

        clicks shouldBe listOf(3 to 0, 1 to 0)
    }

    // ── Drag events ───────────────────────────────────────────────────────

    test("touchDragged inside grid fires onTileDragged with -1 button") {
        val drags = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileDragged(tileX: Int, tileY: Int, button: Int) {
                drags.add(Triple(tileX, tileY, button))
            }
        })
        // pixel (15, 5) → tile (1, 0) with 10×10 tiles; button = -1 (libGDX limitation)
        proc.touchDragged(15, 5, 0)
        drags shouldBe listOf(Triple(1, 0, -1))
    }

    test("touchDragged outside grid fires no onTileDragged") {
        val drags = mutableListOf<Triple<Int, Int, Int>>()
        val proc = processor()
        proc.addListener(object : KotileInputAdapter() {
            override fun onTileDragged(tileX: Int, tileY: Int, button: Int) {
                drags.add(Triple(tileX, tileY, button))
            }
        })
        proc.touchDragged(200, 200, 0) // far outside grid
        drags.shouldBeEmpty()
    }
})
