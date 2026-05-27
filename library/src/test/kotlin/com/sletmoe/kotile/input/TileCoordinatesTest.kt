package com.sletmoe.kotile.input

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [pixelToTile].
 *
 * These tests are pure — no libGDX GL context is required. They cover the
 * pixel-to-tile coordinate translation that [KotileInputProcessor] uses for
 * every mouse event.
 */
class TileCoordinatesTest : FunSpec({

    // ── Basic translation ────────────────────────────────────────────────

    test("top-left pixel maps to tile (0, 0)") {
        pixelToTile(
            screenPixelX = 0, screenPixelY = 0,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (0 to 0)
    }

    test("pixel inside first tile still maps to (0, 0)") {
        pixelToTile(
            screenPixelX = 9, screenPixelY = 9,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (0 to 0)
    }

    test("pixel at start of second tile maps to (1, 0)") {
        pixelToTile(
            screenPixelX = 10, screenPixelY = 0,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (1 to 0)
    }

    test("pixel at start of second row maps to (0, 1)") {
        pixelToTile(
            screenPixelX = 0, screenPixelY = 10,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (0 to 1)
    }

    test("pixel inside last tile maps to the last tile coordinate") {
        // 8 columns × 10px wide → last tile starts at x=70; 4 rows × 10px tall → last tile starts at y=30
        pixelToTile(
            screenPixelX = 79, screenPixelY = 39,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (7 to 3)
    }

    test("arbitrary interior pixel translates correctly") {
        // x=35 / 10 = 3, y=25 / 10 = 2
        pixelToTile(
            screenPixelX = 35, screenPixelY = 25,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe (3 to 2)
    }

    // ── Non-square tiles ─────────────────────────────────────────────────

    test("non-square 16x24 tiles translate correctly") {
        // x=32 / 16 = 2, y=48 / 24 = 2
        pixelToTile(
            screenPixelX = 32, screenPixelY = 48,
            tileWidthPx = 16, tileHeightPx = 24,
            gridWidthInTiles = 5, gridHeightInTiles = 5,
        ) shouldBe (2 to 2)
    }

    test("non-square tiles: last valid pixel in each dimension") {
        // 5 tiles × 16px wide = 80px; last valid x = 79 → tile 4
        // 5 tiles × 24px tall = 120px; last valid y = 119 → tile 4
        pixelToTile(
            screenPixelX = 79, screenPixelY = 119,
            tileWidthPx = 16, tileHeightPx = 24,
            gridWidthInTiles = 5, gridHeightInTiles = 5,
        ) shouldBe (4 to 4)
    }

    // ── Out-of-bounds: returns null ───────────────────────────────────────

    test("pixel one beyond the right edge returns null") {
        // 8 tiles × 10px = 80px; pixel at x=80 is out of bounds
        pixelToTile(
            screenPixelX = 80, screenPixelY = 0,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("pixel one beyond the bottom edge returns null") {
        pixelToTile(
            screenPixelX = 0, screenPixelY = 40,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("negative x returns null") {
        pixelToTile(
            screenPixelX = -1, screenPixelY = 0,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("negative y returns null") {
        pixelToTile(
            screenPixelX = 0, screenPixelY = -1,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("both negative coordinates return null") {
        pixelToTile(
            screenPixelX = -5, screenPixelY = -5,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    // ── Partial-tile strip (letterboxing / non-exact fit) ─────────────────

    test("partial-tile strip on the right is out of bounds") {
        // Grid is 8 × 10px = 80px wide, but window is 85px.
        // Pixel x=82 falls in the partial strip (8th tile doesn't exist at that point).
        pixelToTile(
            screenPixelX = 82, screenPixelY = 5,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("partial-tile strip on the bottom is out of bounds") {
        pixelToTile(
            screenPixelX = 5, screenPixelY = 42,
            tileWidthPx = 10, tileHeightPx = 10,
            gridWidthInTiles = 8, gridHeightInTiles = 4,
        ) shouldBe null
    }

    // ── After simulated resize (tile dimensions change) ───────────────────

    test("translation uses the supplied tile dimensions, not hardcoded values") {
        // Simulate a resize that made tiles 20×20 instead of 10×10.
        // Pixel (25, 45) → tile (1, 2) with 20×20 tiles.
        pixelToTile(
            screenPixelX = 25, screenPixelY = 45,
            tileWidthPx = 20, tileHeightPx = 20,
            gridWidthInTiles = 4, gridHeightInTiles = 3,
        ) shouldBe (1 to 2)
    }

    test("resize to larger tiles shrinks valid pixel range") {
        // With 20px tiles and 3 grid columns: valid x range is [0, 60).
        // Pixel x=60 is the first out-of-bounds pixel (tile index 3 >= gridWidthInTiles 3).
        pixelToTile(
            screenPixelX = 60, screenPixelY = 0,
            tileWidthPx = 20, tileHeightPx = 20,
            gridWidthInTiles = 3, gridHeightInTiles = 4,
        ) shouldBe null
    }

    test("resize with fitToWindow=true: new tile count and size both change") {
        // Suppose a 160×80 window with 16×16 tiles → 10×5 grid.
        // Pixel (144, 64) → tile (9, 4)
        pixelToTile(
            screenPixelX = 144, screenPixelY = 64,
            tileWidthPx = 16, tileHeightPx = 16,
            gridWidthInTiles = 10, gridHeightInTiles = 5,
        ) shouldBe (9 to 4)
    }
})
