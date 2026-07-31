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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
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
                // Cell 0 (full block) is opaque in its core; cell 1 (space) stays black. Sample the
                // central half only: glyph placement centres each glyph in its cell, so cell-filling
                // glyphs don't reach the cell edges yet (seams — the krogue-9x7.4 follow-up), which a
                // full-cell sample would (correctly) catch.
                pixels.averageColor(4, 4, 12, 12).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
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
                // Upright: the full block is solid in its core (~0.9). Flipped: a sparse '+' glyph, far
                // dimmer (~0.2-0.3). Sample the central half (the block doesn't reach the cell edges — see
                // the full-block test's note) so this discriminates orientation, not edge coverage. The
                // 0.8 gate (was 0.9) leaves room for the krogue-ux6 TEXT em-shrink, which makes the
                // cell-filling block a little smaller — its central-half coverage is ~0.90 here — while
                // still cleanly separating the upright block from the flipped '+'.
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
            // top two rows were ~0.71 (jammed against the top) and taper to ~0.04 after the fit.
            val aCeil = glyphCell(slot = 143, snapToPixelGrid = true, cell = cell) { peakRed(it, 0, 2) }
            aCeil.toDouble() shouldBeLessThan 0.5 // the accent no longer jams against the ceiling (was ~0.71)
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
})

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
            val window =
                AsciiTileWindow.create {
                    glyphSource = source
                    widthInTiles = 1
                    heightInTiles = 1
                    fitToWindow = false
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
