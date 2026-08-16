package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.GlyphFit
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
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
                    val source = Fonts.cascadiaMono(16, 16)
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
                // Cell 0 (full block) is opaque across its WHOLE cell; cell 1 (space) stays black. The
                // full-cell sample is the point: cell-filling glyphs are edge-snapped (krogue-9x7.4), so
                // the block reaches the cell edges and tiles with its neighbours. Before that it was
                // ink-centred and a full-cell sample failed here — hence the older central-half samples.
                pixels.averageColor(0, 0, 16, 16).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                pixels.averageColor(20, 4, 28, 12).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
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
                    val source = Fonts.cascadiaMono(16, 16)
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
            // Derive the backbuffer/logical ratio from the shared HeadlessGl window rather than assuming
            // 1 — the atlas is built at the backbuffer cell px, so the expectation must track hidpi.
            var hidpi = 1
            HeadlessGl
                .render(40, 20, Color.BLACK) {
                    hidpi = (Gdx.graphics.backBufferWidth / Gdx.graphics.width.coerceAtLeast(1)).coerceAtLeast(1)
                    val source = Fonts.cascadiaMono(16, 16)
                    val window =
                        AsciiTileWindow.create {
                            glyphSource = source
                            widthInTiles = 4
                            heightInTiles = 2
                            resolutionIndependent = true
                        }
                    try {
                        // 40x20 window, 4x2 grid -> logical cell = min(40/4, 20/2) = 10.
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

            // Canvas layout is the logical cell; the atlas is that times the HiDPI ratio.
            cellAt40 shouldBe 10
            atlasAt40 shouldBe 10 * hidpi
            cellAt80 shouldBe 20 // grew with the window, not stuck at the construction size (16)
            atlasAt80 shouldBe 20 * hidpi
        }

    test("FreeTypeGlyphSource: an odd supersample pass count keeps the atlas upright (not flipped)")
        .config(enabled = HeadlessGl.available) {
            // Guards the atlas-orientation bug: each gamma-halving pass flips the FBO vertically, so a
            // supersample factor with an ODD number of 2:1 passes (8 → 3 passes) leaves the atlas
            // upside-down unless each pass re-flips to preserve orientation. The full block (slot 219)
            // sits at atlas row 13; a vertical flip maps that cell to row 2 (a sparse '+' glyph). So
            // `glyph(219)` renders fully opaque only when the atlas is upright — flipped, it would show
            // '+' and read near-empty. supersample=4 (2 passes) passes even with the bug, so 8 is used.
            //
            // Cell 16 keeps the master atlas at 16·16·8 = 2048px/axis, so it stays under any driver's
            // GL_MAX_TEXTURE_SIZE (≥ 2048 everywhere) and rasterize() does NOT halve `ss` to an even
            // count — otherwise the cap would silently make this pass even against the un-fixed code.
            var effectiveSs = -1
            val pixels =
                HeadlessGl.render(16, 16, Color.BLACK) {
                    val source =
                        FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), 16, 16, supersample = 8)
                    effectiveSs = source.effectiveSupersample
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
                // Guard against a low-GL_MAX_TEXTURE_SIZE driver capping ss down to an even pass count:
                // if it did, this spec would pass WITH the bug present. 8 (3 passes, odd) must survive.
                effectiveSs shouldBe 8
                // Upright: the full block is solid in its core. Flipped: a sparse '+' glyph, far dimmer
                // (~0.2-0.3). Sample the central half so this discriminates ORIENTATION rather than edge
                // coverage (which the krogue-9x7.4 seam specs own). The 0.8 gate still cleanly separates
                // the upright block from the flipped '+'.
                pixels.averageColor(4, 4, 12, 12).r.toDouble() shouldBeGreaterThan 0.8
            } finally {
                pixels.dispose()
            }
        }

    // --- krogue-9x7.3: per-glyph TILE fit + brightness curve. Assertions are RELATIVE (one placement/
    // setting vs another on the same glyph) so they hold regardless of the CI driver's exact AA or the
    // runner's HiDPI ratio. Geometry mirrors the local :kotile:library:freetypeVerify harness (24px cell,
    // single-cell render), which is where these were eyeballed on real pixels. ---

    test("FreeTypeGlyphSource TILE fit: a glyph fills more of its cell than TEXT layout")
        .config(enabled = HeadlessGl.available) {
            // 'A' (slot 65) ink-centred and scaled to fit its cell covers more of the cell interior than
            // the baseline TEXT layout at the same cell size.
            val textFill = glyphCell(slot = 65, fit = GlyphFit.TEXT) { it.averageColor(6, 6, 18, 18).r }
            val tileFill = glyphCell(slot = 65, fit = GlyphFit.TILE) { it.averageColor(6, 6, 18, 18).r }
            tileFill.toDouble() shouldBeGreaterThan (textFill.toDouble() + 0.05)
        }

    test("FreeTypeGlyphSource TILE fit: glyphs render upright (top-heavy 'F' inks its top half more)")
        .config(enabled = HeadlessGl.available) {
            // Guards the TILE region-draw orientation (a raw page-region draw needs no extra V-flip). 'F'
            // is top-heavy; upright, its top half out-inks its bottom half. A flipped atlas inverts that.
            // Pixmap is top-left origin (y down), so the top half is the smaller-y band.
            val (top, bottom) =
                glyphCell(slot = 70, fit = GlyphFit.TILE) {
                    it.averageColor(6, 3, 18, 12).r to it.averageColor(6, 12, 18, 21).r
                }
            top.toDouble() shouldBeGreaterThan (bottom.toDouble() + 0.03)
        }

    test("FreeTypeGlyphSource brightness curve: lifts a sub-peak glyph, leaves a solid one unchanged")
        .config(enabled = HeadlessGl.available) {
            // The light shade (176) never reaches full ink, so peak-normalisation lifts it; the full block
            // (219) is already solid, so the curve is a no-op there (boost = 1).
            val shadeOff = glyphCell(slot = 176, glyphBrightness = 1f) { it.averageColor(6, 6, 18, 18).r }
            val shadeOn = glyphCell(slot = 176, glyphBrightness = 2f) { it.averageColor(6, 6, 18, 18).r }
            shadeOn.toDouble() shouldBeGreaterThan (shadeOff.toDouble() + 0.005)

            val blockOn = glyphCell(slot = 219, glyphBrightness = 2f) { it.averageColor(6, 6, 18, 18).r }
            blockOn.toDouble() shouldBeGreaterThan 0.9
        }

    test("FreeTypeGlyphSource brightness curve: a blank glyph stays transparent (ADR-0029)")
        .config(enabled = HeadlessGl.available) {
            // Space (32) has no ink, so the MIN_INK_ALPHA guard must keep the curve a no-op even at the
            // max cap — a keyed-out/blank glyph stays transparent (ADR-0029), never brightened into a stroke.
            val spaceOn = glyphCell(slot = 32, glyphBrightness = 4f) { it.averageColor(6, 6, 18, 18).r }
            spaceOn.toDouble() shouldBeLessThan 0.05
        }

    test("FreeTypeGlyphSource snapToPixelGrid: the shift-search downsample still yields a sane atlas")
        .config(enabled = HeadlessGl.available) {
            // The CPU shift-search path (Brogue optimizeTiles technique) replaces the GPU halving; sanity-
            // check it produces a usable atlas — full block (219) opaque in its core, space (32) empty.
            val block = glyphCell(slot = 219, snapToPixelGrid = true) { it.averageColor(6, 6, 18, 18).r }
            val space = glyphCell(slot = 32, snapToPixelGrid = true) { it.averageColor(6, 6, 18, 18).r }
            block.toDouble() shouldBeGreaterThan 0.9
            space.toDouble() shouldBeLessThan 0.05
        }

    test("FreeTypeGlyphSource TEXT fit: a descender (g) is not clipped at the cell floor (krogue-ns5)")
        .config(enabled = HeadlessGl.available) {
            // Regression for krogue-ns5. The old placement centred each glyph's CAP box, dropping the
            // baseline so low that a descender was scissored FLAT at the cell floor — leaving the very
            // bottom row fully inked (the cut cross-section, coverage ~1.0). The fix centres the font's
            // LINE box (capHeight + descent), so the tail tapers to nothing at/above the floor. A 32px
            // cell gives the fix room to end the tail above the floor.
            //
            // Discriminator, verified new-vs-old at this exact single-'g' 32px geometry via the macOS
            // fontEval probe: peak coverage in the bottom row is 1.0 with the un-fixed code (hard clip)
            // and 0.0 after the fix. The body band above the floor is strongly inked either way, so it
            // guards the floor check against being satisfied vacuously by an empty/failed render.
            val cell = 32

            fun peakRed(
                pix: Pixmap,
                y0: Int,
                y1: Int,
            ): Float {
                var peak = 0f
                for (y in y0 until y1) {
                    for (x in cell / 4 until cell * 3 / 4) peak = maxOf(peak, pix.averageColor(x, y, x + 1, y + 1).r)
                }
                return peak
            }
            val bodyPeak =
                glyphCell(slot = 'g'.code, snapToPixelGrid = true, cell = cell) {
                    peakRed(it, cell * 5 / 8, cell - 3)
                }
            val floorPeak =
                glyphCell(slot = 'g'.code, snapToPixelGrid = true, cell = cell) {
                    peakRed(it, cell - 1, cell)
                }
            bodyPeak.toDouble() shouldBeGreaterThan 0.5 // the descender is actually rendered
            floorPeak.toDouble() shouldBeLessThan 0.5 // ...and its tail no longer hits the cell floor (was ~1.0)
        }

    test(
        "FreeTypeGlyphSource TEXT fit: tall glyphs (ascender h, accented cap Å) clear the cell top (krogue-ux6)",
    )
        .config(enabled = HeadlessGl.available) {
            // Top-edge mirror of the krogue-ns5 descender test. krogue-ns5 centred the cap box
            // (capHeight+descent ≈ the em) so the cap top sat at ~0.95·cell, leaving almost no headroom:
            // ascenders (b/h/k/l) — and, a row lower, ring/accented caps (Å/Ä/É) — were sheared flat by the
            // per-cell scissor at the cell ceiling. krogue-ux6 shrinks the em so the FULL ink box (the ascent,
            // incl. accent room, plus the descent) fits, and centres that box, reserving top headroom.
            //
            // Discriminators verified new-vs-old at this exact 32px geometry via the macOS freetypeVerify
            // probe: the ascender's top row is a flat stem cut (peak 1.0) with the un-fixed code and 0.0 after
            // the fix; the accented cap's top two rows drop from ~0.71 (jammed at the ceiling) to ~0.04. The
            // body band is strongly inked either way, guarding the ceiling check from passing vacuously.
            val cell = 32

            fun peakRed(
                pix: Pixmap,
                y0: Int,
                y1: Int,
            ): Float {
                var peak = 0f
                for (y in y0 until y1) {
                    for (x in cell / 4 until cell * 3 / 4) peak = maxOf(peak, pix.averageColor(x, y, x + 1, y + 1).r)
                }
                return peak
            }
            // Ascender 'h': hard stem cut => top row fully inked when clipped, empty when it clears.
            val hBody =
                glyphCell(slot = 'h'.code, snapToPixelGrid = true, cell = cell) { peakRed(it, cell / 2, cell - 3) }
            val hCeil = glyphCell(slot = 'h'.code, snapToPixelGrid = true, cell = cell) { peakRed(it, 0, 1) }
            hBody.toDouble() shouldBeGreaterThan 0.5 // the ascender is actually rendered
            hCeil.toDouble() shouldBeLessThan 0.5 // ...and its top no longer hits the cell ceiling (was ~1.0)
            // Accented cap 'Å' (slot 143), the issue's headline case: its ring clears the ceiling too — the
            // top two rows were ~0.71 (jammed against the top) and taper to ~0.04 after the fit. The band
            // just below the ceiling holds the ring itself, so it guards the ceiling check the same way
            // hBody guards 'h' (thresholds mirror the freetypeVerify probe's aBody/aCeil discriminators).
            val aBody =
                glyphCell(slot = 143, snapToPixelGrid = true, cell = cell) { peakRed(it, 4, cell * 3 / 8) }
            val aCeil = glyphCell(slot = 143, snapToPixelGrid = true, cell = cell) { peakRed(it, 0, 2) }
            aBody.toDouble() shouldBeGreaterThan 0.4 // the ring/accent is actually rendered
            aCeil.toDouble() shouldBeLessThan 0.5 // ...and no longer jams against the ceiling (was ~0.71)
        }

    // --- krogue-9x7.5: x-height/baseline band scaling for lowercase crispness (Brogue optimizeTiles
    // part 2). TEXT + snapToPixelGrid WARPS the vertical resample so both the x-height top and the baseline
    // land on whole output rows, with ONE shared vertical map for every cell — the per-glyph translation-only
    // path (reachable via the `disableBandScale` seam — what the code did before this change) can only SHIFT
    // each glyph, not warp. Two driver-independent consequences: (1) the band-scaled atlas differs
    // substantially from the translation-only atlas (the warp changes the resample); (2) flat-bottomed
    // lowercase letters share a single snapped baseline row. The finer blur/eyeball A/B across sizes lives in
    // the :kotile:library:freetypeVerify harness (see renderBandScaleComparison).
    //
    // NOTE (krogue-ux6): an earlier form of this spec asserted `bandSpread < transSpread` at 16px. That is a
    // ≤1px, driver-sensitive knife-edge — and once ux6 shrank the TEXT em to reserve ascender/accent room,
    // the extra vertical headroom let the translation-only shift search also nail the baseline (spread 0),
    // collapsing the strict inequality. The atlas-difference signal below is robust: it is ~30% of inked
    // pixels when band scaling is active and exactly 0 if it is disabled (both paths become translation). ---

    test("FreeTypeGlyphSource band scaling: warps the resample (differs from translation-only) + tight baseline")
        .config(enabled = HeadlessGl.available) {
            // A varied lowercase row (mixing round o/e/c, flat n/u/r/w, and ascenders b/h/k/t) at a 16px cell,
            // rendered band-scaled and translation-only. The two share every input except the band-scale warp.
            val bandRow = renderLowercaseRow(BAND_LETTERS, disableBandScale = false)
            val transRow = renderLowercaseRow(BAND_LETTERS, disableBandScale = true)
            try {
                // Regression signal: band scaling WARPS the vertical resample, so its atlas differs
                // substantially from the translation-only path (~30% of inked pixels here). If band scaling
                // were removed, TEXT+snap IS the translation path and the two rows would be byte-identical
                // (diff 0) — so a healthy diff far above 0 proves band scaling is active and doing its warp.
                val (diff, inked) = pixelDiff(bandRow, transRow)
                inked.toDouble() shouldBeGreaterThan 0.0
                diff.toDouble() shouldBeGreaterThan (inked / 10).toDouble() // ~30% in practice; disabled -> 0

                // And band scaling gives a tight shared baseline: flat-bottom letters land within one output
                // row (only round-letter overshoot remains). Measured on the band-scaled row.
                baselineSpread(bandRow, BAND_FLAT_BOTTOM, BAND_LETTERS).toDouble() shouldBeLessThanOrEqual 1.0
            } finally {
                bandRow.dispose()
                transRow.dispose()
            }
        }

    // --- krogue-9x7.4: cell-filling glyphs (box drawing + blocks) edge-snap to the cell rect so they tile
    // seamlessly at a cell aspect the face doesn't share. Every spec below renders at a SQUARE [SEAM_CELL]
    // cell, which Cascadia Mono's tall design cell does not match — the case that used to leave a gap. ---

    test("FreeTypeGlyphSource: box drawing tiles seamlessly across cell boundaries (krogue-9x7.4)")
        .config(enabled = HeadlessGl.available) {
            // '─' (196) in two horizontally adjacent cells: the stroke must reach both cell edges, so EVERY
            // column of the two-cell strip carries it — including the pair straddling the seam. Un-fixed,
            // the glyph was laid out at its natural advance width and centred, so ~4 columns each side of
            // the boundary were empty (peak 0) — this is the seam the issue is about.
            val horizontal = renderSlotGrid(listOf(196, 196), cols = 2, rows = 1)
            try {
                columnPeaks(horizontal).min() shouldBeGreaterThan SEAM_INKED_PEAK
                // ...and it is a LINE, not a filled/blank cell: rows away from the stroke stay dark, so the
                // column assertion above can't be satisfied by an all-white (or all-black, min 0) render.
                rowPeaks(horizontal).min() shouldBeLessThan SEAM_BLANK_PEAK
            } finally {
                horizontal.dispose()
            }

            // '│' (179) in two vertically adjacent cells — the same, one axis over.
            val vertical = renderSlotGrid(listOf(179, 179), cols = 1, rows = 2)
            try {
                rowPeaks(vertical).min() shouldBeGreaterThan SEAM_INKED_PEAK
                columnPeaks(vertical).min() shouldBeLessThan SEAM_BLANK_PEAK
            } finally {
                vertical.dispose()
            }
        }

    test("FreeTypeGlyphSource: box-drawing seams survive the snapToPixelGrid downsample (krogue-9x7.4)")
        .config(enabled = HeadlessGl.available) {
            // Same strips under snapToPixelGrid, where the downsample re-samples each cell: the sub-pixel
            // shift search (and, for TEXT, the band-scale warp) slides the sampling window, and the clamp at
            // the master edge then shaves the trailing output pixel — re-opening the seam. Cell-filling
            // glyphs are exempt from both (offset-free box downsample), which is what this pins.
            val horizontal = renderSlotGrid(listOf(196, 196), cols = 2, rows = 1, snapToPixelGrid = true)
            try {
                columnPeaks(horizontal).min() shouldBeGreaterThan SEAM_INKED_PEAK
                rowPeaks(horizontal).min() shouldBeLessThan SEAM_BLANK_PEAK
            } finally {
                horizontal.dispose()
            }

            val vertical = renderSlotGrid(listOf(179, 179), cols = 1, rows = 2, snapToPixelGrid = true)
            try {
                rowPeaks(vertical).min() shouldBeGreaterThan SEAM_INKED_PEAK
                columnPeaks(vertical).min() shouldBeLessThan SEAM_BLANK_PEAK
            } finally {
                vertical.dispose()
            }
        }

    // --- krogue-tg5: under snapToPixelGrid, a cell-filling glyph's INTERIOR stroke edges are snapped to
    // whole output rows/columns by a warp that leaves the cell edges pinned (so the 9x7.4 seams above still
    // hold). krogue-9x7.4 had to exempt this class from both snap transforms — a translation or a
    // baseline-relative warp slides the sampling window and the master-edge clamp re-opens the seam — which
    // left the strokes wherever the uniform downsample put them. ---

    test("FreeTypeGlyphSource: box-drawing stroke edges land on whole output rows/columns (krogue-tg5)")
        .config(enabled = HeadlessGl.available) {
            // '─' in one cell under snap: every row is either solid or blank, never half-lit. Un-fixed, the
            // stroke's 2.3-output-row height straddled the grid and the flanking rows read 175/157 — the
            // grey the issue is about. The warp lands the edges on row boundaries: 240/241 with 17 spill.
            val horizontal = renderSlotGrid(listOf(196), cols = 1, rows = 1, snapToPixelGrid = true)
            try {
                val peaks = rowPeaks(horizontal)
                // Not vacuous: there IS a stroke (a solid row) and there IS background (a blank row), so
                // neither an empty cell nor a filled one can satisfy the "no half-lit row" assertion.
                peaks.count { it >= SNAP_SOLID_PEAK } shouldBeGreaterThan 0
                peaks.count { it <= SNAP_BLANK_PEAK } shouldBeGreaterThan 0
                peaks.count { it in (SNAP_BLANK_PEAK + 1) until SNAP_SOLID_PEAK } shouldBe 0
            } finally {
                horizontal.dispose()
            }

            // '│' the same, one axis over (its columns; un-fixed the trailing column read 120).
            val vertical = renderSlotGrid(listOf(179), cols = 1, rows = 1, snapToPixelGrid = true)
            try {
                val peaks = columnPeaks(vertical)
                peaks.count { it >= SNAP_SOLID_PEAK } shouldBeGreaterThan 0
                peaks.count { it <= SNAP_BLANK_PEAK } shouldBeGreaterThan 0
                peaks.count { it in (SNAP_BLANK_PEAK + 1) until SNAP_SOLID_PEAK } shouldBe 0
            } finally {
                vertical.dispose()
            }
        }

    test("FreeTypeGlyphSource: snapped strokes still meet across a MIXED box-drawing seam (krogue-tg5)")
        .config(enabled = HeadlessGl.available) {
            // The warp is measured per glyph, so the new failure mode it could introduce is two DIFFERENT
            // box-drawing glyphs snapping their shared stroke to different rows — a frame that steps at
            // every junction. They must not: the cell-filling placement gives every member of the class the
            // same master stroke positions, so the snap (a function of those) has to agree. Single line
            // '─ ┼ ─' and double line '═ ╬ ═' (two runs per axis, the harder case): each strip's horizontal
            // stroke rows, measured away from the cross's stem, must be the same rows in all three cells,
            // and the strip must stay unbroken across both seams.
            for (family in listOf(listOf(196, 197, 196), listOf(205, 206, 205))) {
                val strip = renderSlotGrid(family, cols = 3, rows = 1, snapToPixelGrid = true)
                try {
                    withClue("family=$family") {
                        // Ink on both sides of each cell boundary. Only the SEAM columns, not every column:
                        // '╬' is four corner pieces around a hollow centre, so its middle columns are
                        // legitimately blank and a whole-strip minimum would fail on a correct render.
                        val cols = columnPeaks(strip)
                        for (seam in listOf(SEAM_CELL, 2 * SEAM_CELL)) {
                            cols[seam - 1] shouldBeGreaterThan SEAM_INKED_PEAK
                            cols[seam] shouldBeGreaterThan SEAM_INKED_PEAK
                        }
                        // Sample each cell's left quarter — clear of the cross's centre stem, which would
                        // otherwise ink every row and make this about the stem rather than the arm.
                        val quarter = SEAM_CELL / 4
                        val rows =
                            (0 until 3).map { i -> strokeRows(strip, i * SEAM_CELL, i * SEAM_CELL + quarter) }
                        rows[0].size shouldBeGreaterThan 0 // the arm is actually there to compare
                        rows[1] shouldBe rows[0]
                        rows[2] shouldBe rows[0]
                    }
                } finally {
                    strip.dispose()
                }
            }
        }

    test("FreeTypeGlyphSource: the full block fills its whole cell, half blocks exactly their half")
        .config(enabled = HeadlessGl.available) {
            // '█' (219) edge to edge — the glyph the design-cell map is measured from, so it is the
            // placement's identity case. Un-fixed it stopped well short of the cell on the axis that
            // didn't bind (which is why the older specs sample only the central half).
            val block = renderSlotGrid(listOf(219), cols = 1, rows = 1)
            try {
                block.averageColor(0, 0, SEAM_CELL, SEAM_CELL).r.toDouble() shouldBeGreaterThan 0.95
            } finally {
                block.dispose()
            }

            // Half blocks pin the map's ORIENTATION as well as its extent: '▄' (220) must ink the cell's
            // bottom half and nothing above it, '▀' (223) the mirror. A vertically mirrored map (reading
            // Glyph.yoffset as a y-DOWN offset) would swap the two and still fill the cell, so a fill-only
            // assertion would miss it. One row of slack around the midpoint for the boundary's antialiasing.
            val half = SEAM_CELL / 2
            val lower = renderSlotGrid(listOf(220), cols = 1, rows = 1)
            try {
                lower.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r.toDouble() shouldBeGreaterThan 0.95
                lower.averageColor(0, 0, SEAM_CELL, half - 1).r.toDouble() shouldBeLessThan 0.05
            } finally {
                lower.dispose()
            }
            val upper = renderSlotGrid(listOf(223), cols = 1, rows = 1)
            try {
                upper.averageColor(0, 0, SEAM_CELL, half - 1).r.toDouble() shouldBeGreaterThan 0.95
                upper.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r.toDouble() shouldBeLessThan 0.05
            } finally {
                upper.dispose()
            }
        }

    // --- krogue-5uw: the snap path reads the framebuffer read-back in its native BOTTOM-UP orientation and
    // inverts the row as it goes (`bottomUpRowStart`) instead of materialising a flipped copy of the
    // supersampled master. Orientation is the one thing that regression can break, and none of the specs
    // above can see it: the half-block spec that *does* assert orientation runs with snapToPixelGrid off,
    // so it never executes the code that reads the master at all. ---

    test("FreeTypeGlyphSource snapToPixelGrid: half blocks keep their orientation (krogue-5uw)")
        .config(enabled = HeadlessGl.available) {
            // Drop the inversion in `bottomUpRowStart` and the whole page comes out vertically mirrored:
            // '▄' inks the cell's TOP half and '▀' its bottom. It still fills exactly half a cell, with the
            // seams and extents intact, so every fill/coverage/seam assertion in this file stays green —
            // only the orientation moves. Snap routes these two through `emitCellFillingCell`'s edge-pinning
            // warp, but that reads the same per-cell SAT `buildCellSat` fills, so the mirrored master
            // reaches it either way.
            val half = SEAM_CELL / 2
            val lower = renderSlotGrid(listOf(220), cols = 1, rows = 1, snapToPixelGrid = true)
            try {
                withClue("'▄' under snap must ink the BOTTOM half — a mirrored master inks the top") {
                    lower.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r.toDouble() shouldBeGreaterThan 0.95
                    lower.averageColor(0, 0, SEAM_CELL, half - 1).r.toDouble() shouldBeLessThan 0.05
                }
            } finally {
                lower.dispose()
            }
            val upper = renderSlotGrid(listOf(223), cols = 1, rows = 1, snapToPixelGrid = true)
            try {
                withClue("'▀' under snap must ink the TOP half — a mirrored master inks the bottom") {
                    upper.averageColor(0, 0, SEAM_CELL, half - 1).r.toDouble() shouldBeGreaterThan 0.95
                    upper.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r.toDouble() shouldBeLessThan 0.05
                }
            } finally {
                upper.dispose()
            }
        }

    // --- krogue-9x7.6: the per-glyph shift search is memoised per rasterise geometry, so a resize back to a
    // cell size this source has already built pays only the downsample. It is a pure memoisation — the atlas
    // is unchanged, so `lastShiftSearchCount` is the only place a hit is visible, and the exact pixel
    // comparison is what proves the cached offsets are the SAME offsets rather than merely cheaper ones.
    // Un-cached, the return leg re-searches the whole searchable page (count == atFirst); cached it is 0. ---

    test("FreeTypeGlyphSource snapToPixelGrid: revisiting a cell size reuses its cached shift search (krogue-9x7.6)")
        .config(enabled = HeadlessGl.available) {
            // TEXT fit: the production snap path (band-scaled vertical + horizontal shift search).
            val (counts, pages) = shiftCacheRoundTrip(GlyphFit.TEXT)
            val (atFirst, atOther, onReturn) = counts
            val (before, after) = pages
            try {
                withClue("a fresh cell size must actually run the search: $counts") {
                    atFirst shouldBeGreaterThan 0
                }
                withClue("the cache is keyed by geometry, so a DIFFERENT cell size still searches: $counts") {
                    atOther shouldBeGreaterThan 0
                }
                withClue("a cell size already rasterised must not search again: $counts") {
                    onReturn shouldBe 0
                }
                val (diff, inked) = exactDiff(before, after)
                // Ink first: an empty (or failed) render would make the zero-diff assertion vacuous.
                withClue("the CP437 page must have ink to compare") { inked shouldBeGreaterThan 0 }
                withClue("cached offsets must reproduce the atlas exactly (differing px of $inked inked)") {
                    diff shouldBe 0
                }
            } finally {
                before.dispose()
                after.dispose()
            }
        }

    test("FreeTypeGlyphSource snapToPixelGrid TILE: the translation-only search is cached per size (krogue-9x7.6)")
        .config(enabled = HeadlessGl.available) {
            // TILE fit takes the other downsample — the 2-D translation search, no band scaling — which
            // caches through the same key with its own `bandScaled` flag, so it needs its own round trip.
            val (counts, pages) = shiftCacheRoundTrip(GlyphFit.TILE)
            val (atFirst, atOther, onReturn) = counts
            val (before, after) = pages
            try {
                withClue("a fresh cell size must actually run the search: $counts") {
                    atFirst shouldBeGreaterThan 0
                }
                withClue("a DIFFERENT cell size still searches: $counts") { atOther shouldBeGreaterThan 0 }
                withClue("a cell size already rasterised must not search again: $counts") {
                    onReturn shouldBe 0
                }
                val (diff, inked) = exactDiff(before, after)
                withClue("the CP437 page must have ink to compare") { inked shouldBeGreaterThan 0 }
                withClue("cached offsets must reproduce the atlas exactly (differing px of $inked inked)") {
                    diff shouldBe 0
                }
            } finally {
                before.dispose()
                after.dispose()
            }
        }

    // --- krogue-wcw: the page is Linear-filtered, so a cell drawn at a magnifying scale samples up to half
    // a texel past its region edge. Packed tight, that half texel is the NEIGHBOURING CP437 slot; with the
    // ADR-0044 extruded gutter it is a copy of the cell's own edge — clamp-to-edge behaviour emulated per
    // cell (the GL wrap mode is per texture, so it guards only the page's outer border). ---

    test("FreeTypeGlyphSource: a magnified cell-filling glyph neither takes nor loses ink at its edges (krogue-wcw)")
        .config(enabled = HeadlessGl.available) {
            // '▄' (220) sits directly right of '█' (219) and directly left of '▌' (221) in the page, and
            // both neighbours are inked along the edge they share with it. Its own top half is empty, so
            // ANY ink up there came from another atlas cell. Un-fixed, the outermost drawn column carries
            // `0.5 - 0.5/scale` of the neighbour's coverage — 0.4375 at BLEED_SCALE, i.e. ~112 of 255 — for
            // every row of that empty half.
            val lower = renderMagnifiedSlot(220)
            try {
                // Stop one magnified row short of the midpoint, where the half block's own edge lands.
                val emptyBelow = BLEED_SPAN / 2 - BLEED_SCALE
                withClue("'▄' has an empty top half; ink there is the neighbouring slot's") {
                    peakIn(lower, 0, 0, BLEED_SPAN, emptyBelow) shouldBeLessThan BLEED_CLEAR_PEAK
                }
                withClue("...and the glyph really is drawn, magnified: its own half is solid edge to edge") {
                    lower
                        .averageColor(0, BLEED_SPAN / 2 + BLEED_SCALE, BLEED_SPAN, BLEED_SPAN)
                        .r
                        .toDouble() shouldBeGreaterThan BLEED_SOLID_MEAN
                }
            } finally {
                lower.dispose()
            }

            // The same defect seen from the other side, and the reason the gutter is EXTRUDED rather than
            // cleared: '█' (219) is solid to all four edges, while the neighbours it samples into are
            // empty at that border ('┌' 218 left, '▄' 220's top half right, 'δ' 235 below). Un-fixed those
            // edge strips are dimmed, not brightened — a transparent gutter would dim them just the same.
            val block = renderMagnifiedSlot(219)
            try {
                val strips =
                    mapOf(
                        "left" to listOf(0, 0, BLEED_SCALE, BLEED_SPAN),
                        "right" to listOf(BLEED_SPAN - BLEED_SCALE, 0, BLEED_SPAN, BLEED_SPAN),
                        "top" to listOf(0, 0, BLEED_SPAN, BLEED_SCALE),
                        "bottom" to listOf(0, BLEED_SPAN - BLEED_SCALE, BLEED_SPAN, BLEED_SPAN),
                    )
                strips.forEach { (edge, r) ->
                    withClue("'█' must stay solid along its $edge edge") {
                        block.averageColor(r[0], r[1], r[2], r[3]).r.toDouble() shouldBeGreaterThan
                            BLEED_SOLID_MEAN
                    }
                }
            } finally {
                block.dispose()
            }
        }
})

// The committed atlas-gutter shape (krogue-wcw). A magnified draw is the case that samples past a region
// edge at all: at BLEED_SCALE the outermost drawn pixels reach past it, so a tight page puts the
// neighbour's ink there and a padded one puts the cell's own edge texel. The thresholds sit either side of
// that gulf, measured on real pixels (macOS, `freetypeVerify`): un-fixed, '▄'s empty half reads a decaying
// 48/25/9 in from its edge and '█'s edge strips drop to 0.78-0.88 of solid; with the gutter, 0 and 0.99+. A small cell keeps the
// capture (BLEED_SPAN square) cheap — this spec is about the packing, not about glyph shape, which the
// crispness specs above own at their own sizes. Internal so the macOS `freetypeVerify` harness mirror
// measures the same geometry from the same literals.
internal const val BLEED_CELL = 16
internal const val BLEED_SCALE = 8
internal const val BLEED_SPAN = BLEED_CELL * BLEED_SCALE
internal const val BLEED_CLEAR_PEAK = 16
internal const val BLEED_SOLID_MEAN = 0.95

/**
 * Renders CP437 [slot] alone, magnified [BLEED_SCALE]× from a [BLEED_CELL] atlas cell, by blitting the
 * source's region **directly** into the capture FBO (a SpriteBatch, no AsciiTileWindow), white on black.
 *
 * Magnification is the whole point: at 1:1 the sample lands on the texel centre and no packing defect can
 * show. Drawing the region alone (rather than a grid of them) is deliberate too — what the cell may sample
 * has to come from the ATLAS's neighbour, not from another quad drawn beside it.
 */
private fun renderMagnifiedSlot(slot: Int): Pixmap =
    HeadlessGl.render(BLEED_SPAN, BLEED_SPAN, Color.BLACK) {
        // Each native resource is owned from the statement that creates it: HeadlessGl's GL context is
        // shared and outlives this test, so anything that throws between two allocations (a SpriteBatch
        // compiles a shader; begin() can fail) must not strand the earlier one in it.
        val source = Fonts.cascadiaMono(BLEED_CELL, BLEED_CELL)
        try {
            val batch = SpriteBatch()
            try {
                val cam =
                    OrthographicCamera().apply {
                        setToOrtho(false, BLEED_SPAN.toFloat(), BLEED_SPAN.toFloat())
                        update()
                    }
                batch.projectionMatrix = cam.combined
                batch.color = Color.WHITE
                batch.begin()
                try {
                    source.glyph(Char(slot))?.let { region ->
                        batch.draw(region, 0f, 0f, BLEED_SPAN.toFloat(), BLEED_SPAN.toFloat())
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

/**
 * Peak intensity (0–255) over the half-open rectangle `[x0, x1) x [y0, y1)` of [pix] — a *peak* rather than
 * a mean because a bleed that touches only the outermost column would average away to nothing.
 *
 * Internal so the macOS `freetypeVerify` harness measures the gutter exactly as the committed spec does.
 */
internal fun peakIn(
    pix: Pixmap,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
): Int {
    var peak = 0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val v = pix.getPixel(x, y) ushr 24 and 0xFF
            if (v > peak) peak = v
        }
    }
    return peak
}

// Cell sizes for the krogue-9x7.6 cache round trip: build at CACHE_CELL, resize away to CACHE_OTHER_CELL,
// then back. Small (a 16px cell is a 256x256 page at ss=4) because the assertion is about *whether* the
// search runs, not about how it looks — the crispness specs above own that at their own sizes. Internal so
// the macOS `:kotile:library:freetypeVerify` mirror runs the round trip at the same geometry this does.
internal const val CACHE_CELL = 16
internal const val CACHE_OTHER_CELL = 20

/**
 * Rasterises a `snapToPixelGrid` source at [CACHE_CELL], walks it to [CACHE_OTHER_CELL] and back, and
 * returns the [FreeTypeGlyphSource.lastShiftSearchCount] after each of the three rasterises together with
 * the CP437 page captured **before** and **after** the round trip (both at [CACHE_CELL]; caller disposes).
 *
 * One source across both captures is the whole point — the cache is per-instance, so a fresh source per
 * capture would measure nothing. The page is blitted 1:1 from the atlas regions (a SpriteBatch, no
 * AsciiTileWindow), so the two captures differ only if the atlas itself differs.
 *
 * That splits the source's lifetime across two renders, which is the one place this file departs from its
 * usual construct-and-dispose-in-one-`try/finally` shape: the second render owns the dispose, so if the
 * FIRST throws it has to dispose before rethrowing or the atlas leaks into [HeadlessGl]'s shared, one-per-
 * JVM GL context and outlives the failing test. Both paths dispose exactly once, on the GL thread.
 */
private fun shiftCacheRoundTrip(fit: GlyphFit): Pair<Triple<Int, Int, Int>, Pair<Pixmap, Pixmap>> {
    val page = COLUMNS_PER_PAGE * CACHE_CELL
    var source: FreeTypeGlyphSource? = null
    var atFirst = -1
    var atOther = -1
    var onReturn = -1

    val before =
        HeadlessGl.render(page, page, Color.BLACK) {
            val src = Fonts.cascadiaMono(CACHE_CELL, CACHE_CELL, fit = fit, snapToPixelGrid = true)
            try {
                source = src
                atFirst = src.lastShiftSearchCount
                blitPage(src, CACHE_CELL)
            } catch (t: Throwable) {
                // The second render — which owns the dispose — will never run, so hand it back here.
                source = null
                src.dispose()
                throw t
            }
        }
    val after =
        HeadlessGl.render(page, page, Color.BLACK) {
            val src = source!!
            try {
                src.prepareForCellSize(CACHE_OTHER_CELL, CACHE_OTHER_CELL)
                atOther = src.lastShiftSearchCount
                src.prepareForCellSize(CACHE_CELL, CACHE_CELL)
                onReturn = src.lastShiftSearchCount
                blitPage(src, CACHE_CELL)
            } finally {
                src.dispose() // on the GL thread, inside the render, like the other helpers here
            }
        }
    return Triple(atFirst, atOther, onReturn) to (before to after)
}

/**
 * Draws all 256 CP437 slots of [source] white-on-black at [cell] px into the currently bound capture FBO
 * (row-major, row 0 at the top), 1:1 from the atlas regions. Mirrors the `freetypeVerify` harness's
 * `renderPageDirect` geometry, minus its FBO management — [HeadlessGl] already owns that here.
 */
private fun blitPage(
    source: FreeTypeGlyphSource,
    cell: Int,
) {
    val page = COLUMNS_PER_PAGE * cell
    val batch = SpriteBatch()
    val cam =
        OrthographicCamera().apply {
            setToOrtho(false, page.toFloat(), page.toFloat())
            update()
        }
    batch.projectionMatrix = cam.combined
    batch.color = Color.WHITE
    batch.begin()
    try {
        for (slot in 0 until 256) {
            val region = source.glyph(Char(slot)) ?: continue
            val x = ((slot % COLUMNS_PER_PAGE) * cell).toFloat()
            val y = (page - (slot / COLUMNS_PER_PAGE + 1) * cell).toFloat() // y-up: slot 0 is the top-left
            batch.draw(region, x, y, cell.toFloat(), cell.toFloat())
        }
    } finally {
        batch.end()
        batch.dispose()
    }
}

/** Columns (and rows) of the 16x16 CP437 page atlas. */
private const val COLUMNS_PER_PAGE = 16

/**
 * Exact per-pixel comparison of two equally-sized captures: `(differing, inked)`. Unlike [pixelDiff] this
 * compares the whole RGBA word with **no** tolerance, because the cache spec's claim is that a cached
 * rasterise is bit-for-bit what the search produced — a tolerance would let a genuinely different
 * downsample pass. `inked` counts pixels carrying ink in either capture (the high byte of libGDX's
 * RGBA8888 word — red, which on these white-on-black captures *is* the coverage; the captures are opaque
 * throughout, so alpha would count every pixel), so an empty or failed render cannot satisfy a zero diff
 * vacuously.
 *
 * Internal so the macOS `freetypeVerify` harness mirror measures the round trip with this exact comparison
 * rather than a drifting copy.
 */
internal fun exactDiff(
    a: Pixmap,
    b: Pixmap,
): Pair<Int, Int> {
    require(a.width == b.width && a.height == b.height) {
        "exactDiff needs equally-sized pixmaps, got ${a.width}x${a.height} vs ${b.width}x${b.height}"
    }
    var diff = 0
    var inked = 0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val pa = a.getPixel(x, y)
            val pb = b.getPixel(x, y)
            if ((pa ushr 24 and 0xFF) > 0 || (pb ushr 24 and 0xFF) > 0) inked++
            if (pa != pb) diff++
        }
    }
    return diff to inked
}

/**
 * Renders a [cols] x [rows] grid of CP437 [slots] (row-major, one slot per cell) at a square [SEAM_CELL]
 * cell by blitting the source's atlas regions **directly** into the capture FBO (a SpriteBatch, no
 * AsciiTileWindow/compositor), white on black, and returns the captured pixels (top-left origin).
 *
 * The cell-filling placement lives entirely in the source's atlas, so a 1:1 region blit exercises it
 * exactly as a window blit would — and mirrors the `:kotile:library:freetypeVerify` harness's `renderGrid`
 * geometry 1:1 (`verifyCellFillSeamsCommittedShape`, where these discriminators were read off real pixels),
 * which a windowed render could not.
 */
private fun renderSlotGrid(
    slots: List<Int>,
    cols: Int,
    rows: Int,
    fit: GlyphFit = GlyphFit.TEXT,
    snapToPixelGrid: Boolean = false,
): Pixmap =
    HeadlessGl.render(cols * SEAM_CELL, rows * SEAM_CELL, Color.BLACK) {
        val source =
            Fonts.cascadiaMono(SEAM_CELL, SEAM_CELL, fit = fit, snapToPixelGrid = snapToPixelGrid)
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, (cols * SEAM_CELL).toFloat(), (rows * SEAM_CELL).toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            slots.forEachIndexed { i, slot ->
                val region = source.glyph(Char(slot)) ?: return@forEachIndexed
                val x = ((i % cols) * SEAM_CELL).toFloat()
                val y = ((rows - (i / cols) - 1) * SEAM_CELL).toFloat() // y-up: row 0 is the top row
                batch.draw(region, x, y, SEAM_CELL.toFloat(), SEAM_CELL.toFloat())
            }
        } finally {
            batch.end()
            batch.dispose()
            source.dispose()
        }
    }

// columnPeaks/rowPeaks are internal so the macOS `freetypeVerify` harness mirror measures the seam the
// same way this spec does (it has to render through its own main-thread GL path, so the measurement is
// the part that can be shared rather than re-implemented).

/** Peak intensity (0–255) of each column of [pix] — a horizontal stroke's continuity profile. */
internal fun columnPeaks(pix: Pixmap): List<Int> =
    (0 until pix.width).map { x ->
        (0 until pix.height).maxOf { y -> pix.getPixel(x, y) ushr 24 and 0xFF }
    }

/** Peak intensity (0–255) of each row of [pix] — a vertical stroke's continuity profile. */
internal fun rowPeaks(pix: Pixmap): List<Int> =
    (0 until pix.height).map { y ->
        (0 until pix.width).maxOf { x -> pix.getPixel(x, y) ushr 24 and 0xFF }
    }

/**
 * The rows of [pix] whose **mean** coverage over columns `[x0, x1)` is solid — the horizontal stroke rows
 * of a box-drawing cell, measured away from any vertical stem so a crossing stroke doesn't ink every row
 * (`┼`'s stem makes its per-row *peak* 255 everywhere, which is why this averages instead).
 *
 * Internal so the macOS `freetypeVerify` harness measures the mixed-glyph seam exactly as this spec does.
 */
internal fun strokeRows(
    pix: Pixmap,
    x0: Int,
    x1: Int,
): List<Int> =
    (0 until pix.height).filter { y ->
        var sum = 0
        for (x in x0 until x1) sum += pix.getPixel(x, y) ushr 24 and 0xFF
        sum.toDouble() / (x1 - x0) >= SNAP_SOLID_PEAK
    }

// The committed stroke-snap shape (krogue-tg5). A stroke edge that lands on a whole output row/column
// leaves every line of the cell either solid or blank; one that straddles leaves a half-lit line. These
// bracket that: on real pixels at SEAM_CELL, the un-fixed (offset-free box downsample) code leaves '─'
// rows at 175/157 and '│' columns at 120, and the edge-pinning warp leaves 240/241 and 225+ with the
// spill below 26. Internal so the `freetypeVerify` harness mirror shares the literals.
internal const val SNAP_SOLID_PEAK = 191 // >= this is a solid line (0.75)
internal const val SNAP_BLANK_PEAK = 64 // <= this is blank (0.25); anything between straddles

// The committed cell-filling/seam shape (krogue-9x7.4). Shared (internal) so the macOS `freetypeVerify`
// harness's verifyCellFillSeamsCommittedShape mirrors this spec from the *same* literals rather than
// drift-prone copies — the harness exists to predict this GL spec, so its geometry must not diverge.
//
// A SQUARE cell is the point: Cascadia Mono's own cell is tall (advance ≈ 0.6 × line height), so 24x24 is
// exactly the "cell aspect != the font's" case where ink-centred box drawing left a gap.
internal const val SEAM_CELL = 24

// A column/row carrying the stroke peaks near full ink; one that lost it is near zero. The thresholds sit
// either side of that gulf rather than on a knife-edge: observed on real pixels, a continuous stroke's
// weakest line is ~255 and the un-fixed code's seam columns are 0.
internal const val SEAM_INKED_PEAK = 128
internal const val SEAM_BLANK_PEAK = 32

/**
 * Renders a single row of [letters] (one CP437 slot per cell) by blitting a band-scaled or translation-only
 * ([disableBandScale]) TEXT [FreeTypeGlyphSource]'s atlas regions **directly** into the capture FBO (a
 * SpriteBatch, no AsciiTileWindow/compositor), white on black, and returns the captured pixels (top-left
 * origin). The band scaling lives entirely in the source's atlas, so a direct region blit exercises it
 * exactly as a window blit would — and mirrors the `:kotile:library:freetypeVerify` harness's `renderGrid`
 * geometry 1:1 (where band=0 / translation=1 baseline spread was observed), avoiding the window-resize
 * coupling that a windowed render would add.
 */
private fun renderLowercaseRow(
    letters: String,
    disableBandScale: Boolean,
): Pixmap =
    HeadlessGl.render(letters.length * BAND_CELL, BAND_CELL, Color.BLACK) {
        val source =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                BAND_CELL,
                BAND_CELL,
                supersample = 8,
                snapToPixelGrid = true,
                disableBandScale = disableBandScale,
            )
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, (letters.length * BAND_CELL).toFloat(), BAND_CELL.toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            letters.forEachIndexed { i, c ->
                val region = source.glyph(c) ?: return@forEachIndexed
                batch.draw(region, (i * BAND_CELL).toFloat(), 0f, BAND_CELL.toFloat(), BAND_CELL.toFloat())
            }
        } finally {
            batch.end()
            batch.dispose()
            source.dispose()
        }
    }

/**
 * Spread (max − min) of the bottom inked row across the [letters]-row cells whose character is in
 * [consider], in [pix] (a row of [BAND_CELL]-wide cells, char i at column i). Tight = a shared baseline.
 * Does **not** dispose [pix] — the caller owns it (the band-scale spec reuses the same row for [pixelDiff]).
 *
 * Fails fast if too few cells inked (a broken atlas, wrong threshold, or empty capture): otherwise an
 * empty sample would yield a value that satisfies the spread assertions vacuously — coverage that isn't
 * there. Every considered letter here has ink, so a healthy render measures ~all of them.
 */
private fun baselineSpread(
    pix: Pixmap,
    consider: String,
    letters: String,
): Int {
    val bottoms = ArrayList<Int>()
    letters.forEachIndexed { i, c ->
        if (c !in consider) return@forEachIndexed
        val x0 = i * BAND_CELL
        var bottom = -1
        for (y in 0 until BAND_CELL) {
            var inked = false
            for (x in x0 + 2 until x0 + BAND_CELL - 2) {
                if ((pix.getPixel(x, y) ushr 24 and 0xFF) > 96) {
                    inked = true
                    break
                }
            }
            if (inked) bottom = y
        }
        if (bottom >= 0) bottoms += bottom
    }
    check(bottoms.size >= MIN_MEASURED_CELLS) {
        "baselineSpread inked only ${bottoms.size} cells (need ≥ $MIN_MEASURED_CELLS) — atlas/capture broken"
    }
    return bottoms.max() - bottoms.min()
}

/**
 * Compares two equally-sized atlas rows by coverage (alpha): returns `(diffPx, inkedPx)` where `diffPx`
 * counts pixels whose alpha differs by more than [ALPHA_EPS] and `inkedPx` counts pixels inked in either.
 * The band-scale spec's robust regression signal (krogue-ux6): band scaling warps the vertical resample,
 * so `diffPx` is a large fraction of `inkedPx`; with band scaling disabled the two paths are identical and
 * `diffPx` is 0. Neither pixmap is disposed.
 */
private fun pixelDiff(
    a: Pixmap,
    b: Pixmap,
): Pair<Int, Int> {
    // Enforce the "equally-sized" precondition rather than trusting it: Pixmap.getPixel returns 0 outside
    // the bitmap instead of throwing, so a mismatch would silently deflate both counts — and a deflated
    // `inked` is exactly what would let the diff ratio below pass vacuously.
    require(a.width == b.width && a.height == b.height) {
        "pixelDiff needs equally-sized pixmaps, got ${a.width}x${a.height} vs ${b.width}x${b.height}"
    }
    var diff = 0
    var inked = 0
    for (y in 0 until a.height) {
        for (x in 0 until a.width) {
            val a1 = a.getPixel(x, y) ushr 24 and 0xFF
            val a2 = b.getPixel(x, y) ushr 24 and 0xFF
            if (a1 > ALPHA_EPS || a2 > ALPHA_EPS) inked++
            if (kotlin.math.abs(a1 - a2) > ALPHA_EPS) diff++
        }
    }
    return diff to inked
}

// Alpha (0–255) tolerance for treating a pixel as inked / as differing between two atlases (~12%).
// Internal so the `:kotile:library:freetypeVerify` harness mirror (verifyBandScaleCommittedShape) uses the
// same threshold rather than a drifting copy.
internal const val ALPHA_EPS = 32

// The committed band-scale shape (krogue-9x7.5). Shared (internal) so the macOS `freetypeVerify` harness's
// verifyBandScaleCommittedShape mirrors this spec from the *same* literals rather than drift-prone copies —
// the harness exists to predict this GL spec, so its geometry must not diverge silently.
internal const val BAND_CELL = 16
internal const val BAND_LETTERS = "thequickbrownfoxjumpslazy"
internal const val BAND_FLAT_BOTTOM = "theuickbrownfoxmslaz" // baseline-sitting letters only: exclude q,j,p,y

// A healthy 16px render of the band-scale row inks a bottom row for every considered (descender-free)
// letter — ~21 of them. This floor still catches a broken/empty atlas (0 inked) without being so tight a
// single sub-threshold glyph on an unusual driver trips it.
internal const val MIN_MEASURED_CELLS = 12

/**
 * Renders CP437 [slot] into a single [cell]x[cell] cell through a fresh [FreeTypeGlyphSource] built with
 * the given [fit]/[glyphBrightness], passes the captured pixels (top-left origin) to [sample], and
 * disposes them. The window owns and disposes the source. Mirrors the harness's single-cell geometry so
 * the committed assertions match what was eyeballed locally.
 */
private fun <T> glyphCell(
    slot: Int,
    fit: GlyphFit = GlyphFit.TEXT,
    glyphBrightness: Float = 1f,
    snapToPixelGrid: Boolean = false,
    cell: Int = 24,
    sample: (Pixmap) -> T,
): T {
    val pixels =
        HeadlessGl.render(cell, cell, Color.BLACK) {
            val source =
                Fonts.cascadiaMono(
                    cell,
                    cell,
                    fit = fit,
                    glyphBrightness = glyphBrightness,
                    snapToPixelGrid = snapToPixelGrid,
                )
            // Ownership transfers to the window (ownsGlyphSource defaults true), so `window.dispose()` below
            // disposes `source` — disposing it again here would double-free the native FreeType face. The one
            // gap is create() itself throwing, where nothing has taken ownership yet: dispose and rethrow.
            val window =
                try {
                    AsciiTileWindow.create {
                        glyphSource = source
                        widthInTiles = 1
                        heightInTiles = 1
                        fitToWindow = false
                    }
                } catch (t: Throwable) {
                    source.dispose()
                    throw t
                }
            try {
                window.drawTile(0, 0, StaticAsciiTile(Char(slot), Color.WHITE, Color.BLACK))
                window.render()
            } finally {
                window.dispose()
            }
        }
    try {
        return sample(pixels)
    } finally {
        pixels.dispose()
    }
}
