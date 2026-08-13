package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileScaling
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * GL-gated tests for the artist-tilesheet glyph source (krogue-9x7.7, ADR-0043). The resampling maths
 * itself is pure and covered GL-free by
 * [com.sletmoe.kotile.display.ascii.TileSheetResampleTest]; what needs a GL context — and so only runs on
 * CI, or locally via `:kotile:library:tileSheetVerify` on macOS — is the part that turns a downscaled
 * sheet into a texture the grid draws: the atlas upload, the per-tile regions, re-rasterising on resize,
 * and (the whole point of [TileInk]) whether a tile still takes the cell's foreground colour.
 *
 * Each test writes its own synthetic sheet to a temp PNG: a 2x2 grid of 64px master tiles —
 * slot 0 a full-bleed solid, slot 1 a centred square with a margin, slot 2 empty, slot 3 solid blue.
 */
class TileSheetGlyphSourceIntegrationTest : FunSpec({

    /** Writes the 128x128 synthetic sheet described above and returns its handle. */
    fun writeSheet(): FileHandle {
        val pixmap =
            Pixmap(128, 128, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
                // Slot 0: solid white, edge to edge (a wall-like, full-bleed tile).
                setColor(Color.WHITE)
                fillRectangle(0, 0, 64, 64)
                // Slot 1: a white square centred in its tile, with a clear margin (a sprite-like tile).
                fillRectangle(64 + 16, 16, 32, 32)
                // Slot 2 (row 1, col 0) is left transparent.
                // Slot 3: solid blue, for the full-colour ink.
                setColor(Color.BLUE)
                fillRectangle(64, 64, 64, 64)
            }
        val temp = File.createTempFile("kotile-tilesheet", ".png").apply { deleteOnExit() }
        val file = Gdx.files.absolute(temp.absolutePath)
        try {
            PixmapIO.writePNG(file, pixmap)
        } finally {
            pixmap.dispose()
        }
        return file
    }

    test("TileSheetGlyphSource: a coverage sheet tints per cell and an empty tile stays clear")
        .config(enabled = HeadlessGl.available) {
            var regionSize = -1 to -1
            var masterSize = -1 to -1
            var tiles = -1
            var outOfRangeGlyph = true
            val pixels =
                HeadlessGl.render(64, 16, Color.BLACK) {
                    val source = TileSheetGlyphSource(writeSheet(), columns = 2, rows = 2, 16, 16)
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 4
                            heightInTiles = 1
                            fitToWindow = false
                        }
                    try {
                        // Slot 0 (the full-bleed solid) in RED; slot 2 (the empty tile) in cell 1.
                        window.drawTile(0, 0, StaticAsciiTile(Char(0), Color.RED, Color.BLACK))
                        window.drawTile(1, 0, StaticAsciiTile(Char(2), Color.RED, Color.BLACK))
                        window.render()

                        val first = source.glyph(Char(0))
                        regionSize = (first?.regionWidth ?: -1) to (first?.regionHeight ?: -1)
                        masterSize = source.masterTileWidthPx to source.masterTileHeightPx
                        tiles = source.tileCount
                        outOfRangeGlyph = source.glyph(Char(4)) != null
                    } finally {
                        window.dispose()
                    }
                }

            try {
                regionSize shouldBe (16 to 16)
                masterSize shouldBe (64 to 64)
                tiles shouldBe 4
                outOfRangeGlyph shouldBe false
                withClue("a COVERAGE tile is white-on-alpha, so it takes the cell's foreground colour") {
                    val inked = pixels.averageColor(0, 0, 16, 16)
                    inked.r.toDouble() shouldBe (1.0 plusOrMinus 0.05)
                    inked.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                    inked.b.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                }
                withClue("the transparent tile inks nothing") {
                    // All three channels, not just red: a leak that tinted the empty cell green or
                    // blue would otherwise pass, since the cell's foreground here is pure red.
                    val empty = pixels.averageColor(16, 0, 32, 16)
                    empty.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                    empty.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                    empty.b.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                }
            } finally {
                pixels.dispose()
            }
        }

    test("TileSheetGlyphSource: FULL_COLOR carries the sheet's own colour through the downscale")
        .config(enabled = HeadlessGl.available) {
            val pixels =
                HeadlessGl.render(32, 16, Color.BLACK) {
                    val source =
                        TileSheetGlyphSource(
                            writeSheet(),
                            columns = 2,
                            rows = 2,
                            16,
                            16,
                            ink = TileInk.FULL_COLOR,
                        )
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 2
                            heightInTiles = 1
                            fitToWindow = false
                        }
                    try {
                        // Slot 3 is solid blue in the sheet. Drawn with a WHITE foreground, which is the
                        // contract for full-colour art: the tint multiplies, so white passes it through.
                        window.drawTile(0, 0, StaticAsciiTile(Char(3), Color.WHITE, Color.BLACK))
                        window.render()
                    } finally {
                        window.dispose()
                    }
                }

            try {
                val inked = pixels.averageColor(0, 0, 16, 16)
                withClue("the tile should still be blue, not collapsed to a coverage mask") {
                    inked.b.toDouble() shouldBe (1.0 plusOrMinus 0.05)
                    inked.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                }
            } finally {
                pixels.dispose()
            }
        }

    test("TileSheetGlyphSource: PRESERVE_ASPECT letterboxes a square tile in an oblong cell")
        .config(enabled = HeadlessGl.available) {
            val pixels =
                HeadlessGl.render(16, 32, Color.BLACK) {
                    val source =
                        TileSheetGlyphSource(
                            writeSheet(),
                            columns = 2,
                            rows = 2,
                            cellWidthPx = 16,
                            cellHeightPx = 32,
                            scaling = TileScaling.PRESERVE_ASPECT,
                        )
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 1
                            heightInTiles = 1
                            fitToWindow = false
                        }
                    try {
                        window.drawTile(0, 0, StaticAsciiTile(Char(0), Color.WHITE, Color.BLACK))
                        window.render()
                    } finally {
                        window.dispose()
                    }
                }

            try {
                // The 64x64 master fits a 16x32 cell as a 16x16 band centred vertically: the middle is
                // inked and the top/bottom eighths are not. STRETCH would fill the whole cell.
                withClue("the centre band carries the tile") {
                    pixels.averageColor(0, 12, 16, 20).r.toDouble() shouldBe (1.0 plusOrMinus 0.05)
                }
                withClue("the letterbox stays clear") {
                    pixels.averageColor(0, 0, 16, 4).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                    pixels.averageColor(0, 28, 16, 32).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
                }
            } finally {
                pixels.dispose()
            }
        }

    test("TileSheetGlyphSource: prepareForCellSize re-downscales the sheet at the new size")
        .config(enabled = HeadlessGl.available) {
            var before = -1 to -1
            var after = -1 to -1
            var regionAfter = -1 to -1
            var sameSizeIsANoOp = false
            HeadlessGl
                .render(1, 1, Color.BLACK) {
                    val source = TileSheetGlyphSource(writeSheet(), columns = 2, rows = 2, 16, 16)
                    try {
                        before = source.charWidthPx to source.charHeightPx
                        val atlasBefore = source.glyph(Char(0))?.texture
                        source.prepareForCellSize(16, 16)
                        // An unchanged size must not rebuild the atlas -- the same texture object survives.
                        sameSizeIsANoOp = source.glyph(Char(0))?.texture === atlasBefore
                        source.prepareForCellSize(24, 28)
                        after = source.charWidthPx to source.charHeightPx
                        val region = source.glyph(Char(0))
                        regionAfter = (region?.regionWidth ?: -1) to (region?.regionHeight ?: -1)
                    } finally {
                        source.dispose()
                    }
                }.dispose()

            before shouldBe (16 to 16)
            sameSizeIsANoOp shouldBe true
            after shouldBe (24 to 28)
            regionAfter shouldBe (24 to 28)
        }

    test("TileSheetGlyphSource: a magnified tile neither takes nor loses ink at its edges (krogue-wcw)")
        .config(enabled = HeadlessGl.available) {
            // The atlas is Linear-filtered, so a tile drawn at a magnifying scale samples up to half a
            // texel past its region edge — the neighbouring tile, in a tightly packed page. Slot 1 (the
            // centred square, clear margin all round) sits directly right of slot 0 (full-bleed solid), so
            // any ink in that left margin is slot 0's. Un-fixed the outermost drawn column carries
            // `0.5 - 0.5/scale` of it: 0.4375 at BLEED_TILE_SCALE, i.e. ~112 of 255.
            val sprite = renderMagnifiedTile(1, ::writeSheet)
            try {
                // The master's margin is a quarter of the tile; sample most of it, clear of the square's edge.
                val margin = BLEED_TILE_SPAN / 4 - BLEED_TILE_SCALE
                withClue("slot 1's left margin is empty; ink there is slot 0 bleeding across the seam") {
                    peakIn(sprite, 0, 0, margin, BLEED_TILE_SPAN) shouldBeLessThan BLEED_CLEAR_PEAK
                }
                withClue("...and the tile really is drawn, magnified: its centre square is solid") {
                    val third = BLEED_TILE_SPAN / 3
                    sprite
                        .averageColor(third, third, BLEED_TILE_SPAN - third, BLEED_TILE_SPAN - third)
                        .r
                        .toDouble() shouldBeGreaterThan BLEED_SOLID_MEAN
                }
            } finally {
                sprite.dispose()
            }

            // The other side of the same defect, and why the gutter is EXTRUDED rather than cleared: slot 0
            // is full-bleed (a wall tile — it must meet its neighbours edge to edge) and what sits beside
            // it in the page is empty: slot 1's clear margin to its right, the transparent slot 2 below.
            // Un-fixed those two edges are dimmed, not brightened; a transparent gutter would dim them
            // just the same. Its left and top edges are the page's own border, where the texture's
            // clamp-to-edge wrap already did this job — asserted with the rest because the property is
            // "solid all round", but they are not what discriminates the fix.
            val wall = renderMagnifiedTile(0, ::writeSheet)
            try {
                val strips =
                    mapOf(
                        "left" to listOf(0, 0, BLEED_TILE_SCALE, BLEED_TILE_SPAN),
                        "right" to listOf(BLEED_TILE_SPAN - BLEED_TILE_SCALE, 0, BLEED_TILE_SPAN, BLEED_TILE_SPAN),
                        "top" to listOf(0, 0, BLEED_TILE_SPAN, BLEED_TILE_SCALE),
                        "bottom" to listOf(0, BLEED_TILE_SPAN - BLEED_TILE_SCALE, BLEED_TILE_SPAN, BLEED_TILE_SPAN),
                    )
                strips.forEach { (edge, r) ->
                    withClue("the full-bleed tile must stay solid along its $edge edge") {
                        wall.averageColor(r[0], r[1], r[2], r[3]).r.toDouble() shouldBeGreaterThan
                            BLEED_SOLID_MEAN
                    }
                }
            } finally {
                wall.dispose()
            }
        }

    test("TileSheetGlyphSource: a sheet that does not divide into whole tiles is rejected")
        .config(enabled = HeadlessGl.available) {
            var message: String? = null
            var type: String? = null
            HeadlessGl
                .render(1, 1, Color.BLACK) {
                    val failure =
                        runCatching {
                            // 128x128 sheet, 5 columns: 128 / 5 is not a whole tile.
                            TileSheetGlyphSource(writeSheet(), columns = 5, rows = 2, 16, 16)
                        }.exceptionOrNull()
                    message = failure?.message
                    // The type matters as much as the text: runCatching swallows anything, so without it a
                    // GL failure before validation could masquerade as the rejection under test.
                    type = failure?.let { it::class.simpleName }
                }.dispose()

            type shouldBe "IllegalArgumentException"
            message shouldBe "sheet 128x128 does not divide into 5x2 whole tiles"
        }
})

// The krogue-wcw magnified-draw geometry for this sheet: a 16px cell blown up 8×, matching the freetype
// spec's [BLEED_SCALE] (and reusing its [BLEED_CLEAR_PEAK] / [BLEED_SOLID_MEAN] thresholds) because the
// packing under test is the same packing — one defect, one fix, one set of literals.
internal const val BLEED_TILE_CELL = 16
internal const val BLEED_TILE_SCALE = 8
internal const val BLEED_TILE_SPAN = BLEED_TILE_CELL * BLEED_TILE_SCALE

/**
 * Renders tile [slot] of the synthetic sheet alone, magnified [BLEED_TILE_SCALE]× from a
 * [BLEED_TILE_CELL] atlas cell, by blitting the source's region **directly** into the capture FBO (a
 * SpriteBatch, no AsciiTileWindow), white on black.
 *
 * Magnification is the whole point: at 1:1 the sample lands on the texel centre and no packing defect can
 * show. One region alone, so anything it picks up came from the ATLAS's neighbour rather than from another
 * quad drawn beside it. [sheet] is the same 2x2 master the other specs use.
 */
private fun renderMagnifiedTile(
    slot: Int,
    sheet: () -> FileHandle,
): Pixmap =
    HeadlessGl.render(BLEED_TILE_SPAN, BLEED_TILE_SPAN, Color.BLACK) {
        // Each native resource is owned from the statement that creates it: HeadlessGl's GL context is
        // shared and outlives this test, so anything that throws between two allocations (a SpriteBatch
        // compiles a shader; begin() can fail) must not strand the earlier one in it.
        val source =
            TileSheetGlyphSource(sheet(), columns = 2, rows = 2, BLEED_TILE_CELL, BLEED_TILE_CELL)
        try {
            val batch = SpriteBatch()
            try {
                val cam =
                    OrthographicCamera().apply {
                        setToOrtho(false, BLEED_TILE_SPAN.toFloat(), BLEED_TILE_SPAN.toFloat())
                        update()
                    }
                batch.projectionMatrix = cam.combined
                batch.color = Color.WHITE
                batch.begin()
                try {
                    source.glyph(Char(slot))?.let { region ->
                        batch.draw(region, 0f, 0f, BLEED_TILE_SPAN.toFloat(), BLEED_TILE_SPAN.toFloat())
                    }
                } finally {
                    batch.end()
                }
            } finally {
                batch.dispose()
            }
        } finally {
            source.dispose()
        }
    }
