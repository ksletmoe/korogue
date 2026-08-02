package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileScaling
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
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
    fun writeSheet(): com.badlogic.gdx.files.FileHandle {
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
                    pixels.averageColor(16, 0, 32, 16).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
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
