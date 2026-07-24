package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * GL-gated tests for the tier-3 freetype glyph source (krogue-9x7.2). Constructing
 * a [com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource] rasterises through an
 * offscreen buffer, so a GL context is required. Skipped unless one is available —
 * on macOS these do not run locally (see the `:kotile:library:freetypeVerify`
 * harness); CI runs them under xvfb + llvmpipe. See [HeadlessGl].
 */
class FreeTypeGlyphSourceIntegrationTest : FunSpec({

    test("FreeTypeGlyphSource: CP437 slots rasterise (full block opaque, space empty) and out-of-range is null")
        .config(enabled = HeadlessGl.available) {
            var blockRegionSize = -1 to -1
            var spaceHasGlyph = true
            var outOfRangeGlyph = true
            val pixels =
                HeadlessGl.render(64, 16, Color.BLACK) {
                    val source = Fonts.ubuntuMono(16, 16)
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 4
                            heightInTiles = 1
                            fitToWindow = false
                        }
                    try {
                        // Full block (slot 219) in cell 0, space (slot 32) in cell 1.
                        window.drawTile(0, 0, StaticAsciiTile(Char(219), Color.WHITE, Color.BLACK))
                        window.drawTile(1, 0, StaticAsciiTile(Char(32), Color.WHITE, Color.BLACK))
                        window.render()

                        val block = source.glyph(Char(219))
                        blockRegionSize = (block?.regionWidth ?: -1) to (block?.regionHeight ?: -1)
                        // A space's slot has a glyph region, but it inks nothing (asserted via pixels below).
                        spaceHasGlyph = source.glyph(Char(32)) != null
                        // char.code beyond the 256-slot page is out of repertoire, like the bitmap Font.
                        outOfRangeGlyph = source.glyph(Char(9999)) != null
                    } finally {
                        window.dispose()
                    }
                }

            try {
                blockRegionSize shouldBe (16 to 16)
                spaceHasGlyph shouldBe true
                outOfRangeGlyph shouldBe false
                // Cell 0 (full block) is near-white; cell 1 (space) stays black.
                pixels.averageColor(2, 2, 14, 14).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                pixels.averageColor(18, 2, 30, 14).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            } finally {
                pixels.dispose()
            }
        }

    test("FreeTypeGlyphSource: prepareForCellSize re-rasterises at the new size")
        .config(enabled = HeadlessGl.available) {
            var before = -1 to -1
            var after = -1 to -1
            var blockAfter = -1 to -1
            HeadlessGl
                .render(1, 1, Color.BLACK) {
                    val source = Fonts.ubuntuMono(16, 16)
                    try {
                        before = source.charWidthPx to source.charHeightPx
                        source.prepareForCellSize(24, 28)
                        after = source.charWidthPx to source.charHeightPx
                        val block = source.glyph(Char(219))
                        blockAfter = (block?.regionWidth ?: -1) to (block?.regionHeight ?: -1)
                    } finally {
                        source.dispose()
                    }
                }.dispose()

            before shouldBe (16 to 16)
            after shouldBe (24 to 28)
            blockAfter shouldBe (24 to 28) // regions reflect the new atlas size
        }

    test("AsciiTileWindow resolutionIndependent: re-rasterises the source to the on-screen cell px on resize")
        .config(enabled = HeadlessGl.available) {
            // The cell COUNT is fixed (4x2); the cell PIXEL size tracks the window, and the freetype
            // source is re-rasterised to match so glyphs are drawn at — not scaled to — the display size.
            // On CI (non-HiDPI) the logical cell and the atlas px coincide; the point here is that both
            // follow the window rather than staying at the construction size.
            var cellAt40 = -1
            var atlasAt40 = -1
            var cellAt80 = -1
            var atlasAt80 = -1
            HeadlessGl
                .render(40, 20, Color.BLACK) {
                    val source = Fonts.ubuntuMono(16, 16)
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 4
                            heightInTiles = 2
                            resolutionIndependent = true
                        }
                    try {
                        // 40x20 window, 4x2 grid -> cell = min(40/4, 20/2) = 10.
                        window.resize(40, 20)
                        cellAt40 = window.tileWidthPx
                        atlasAt40 = source.charWidthPx
                        // Grow the window -> cell grows and the source re-rasterises to match.
                        window.resize(80, 40)
                        cellAt80 = window.tileWidthPx
                        atlasAt80 = source.charWidthPx
                    } finally {
                        window.dispose()
                    }
                }.dispose()

            cellAt40 shouldBe 10
            atlasAt40 shouldBe 10 // hidpi=1 on CI, so atlas px == logical cell px
            cellAt80 shouldBe 20
            atlasAt80 shouldBe 20 // grew with the window, not stuck at the construction size (16)
        }

    test("FreeTypeGlyphSource: an odd supersample pass count keeps the atlas upright (not flipped)")
        .config(enabled = HeadlessGl.available) {
            // Guards the atlas-orientation bug: each gamma-halving pass flips the FBO vertically, so a
            // supersample factor with an ODD number of 2:1 passes (8 → 3 passes) leaves the atlas
            // upside-down unless each pass re-flips to preserve orientation. The full block (slot 219)
            // sits at atlas row 13; a vertical flip maps that cell to row 2 (a sparse '+' glyph). So
            // `glyph(219)` renders fully opaque only when the atlas is upright — flipped, it would show
            // '+' and read near-empty. supersample=4 (2 passes) passes even with the bug, so 8 is used.
            val pixels =
                HeadlessGl.render(24, 24, Color.BLACK) {
                    val source =
                        FreeTypeGlyphSource(Gdx.files.classpath("fonts/UbuntuMono-R.ttf"), 24, 24, supersample = 8)
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 1
                            heightInTiles = 1
                            fitToWindow = false
                        }
                    try {
                        window.drawTile(0, 0, StaticAsciiTile(Char(219), Color.WHITE, Color.BLACK))
                        window.render()
                    } finally {
                        window.dispose()
                    }
                }

            try {
                // Upright: the full block fills the cell. Flipped: a sparse '+' glyph, far dimmer.
                pixels.averageColor(2, 2, 22, 22).r.toDouble() shouldBeGreaterThan 0.9
            } finally {
                pixels.dispose()
            }
        }
})
