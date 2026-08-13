package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.rendering.GammaColor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The CPU resampler behind [TileSheetGlyphSource] (ADR-0043, krogue-9x7.7): it shrinks one
 * **high-resolution** master tile to the on-screen cell size at an **arbitrary, non-integer** ratio,
 * area-averaging each output pixel's source footprint — and, optionally, aligning the tile to the output
 * pixel grid with the same sub-pixel shift search [FreeTypeGlyphSource] uses (ADR-0037, krogue-9x7.3).
 *
 * ## Why this is not [FreeTypeGlyphSource]'s downsample
 *
 * That one rasterises its master at exactly `supersample`× the cell, so the ratio is a power of two and
 * the shrink is a chain of exact GPU 2:1 halvings ([com.sletmoe.kotile.rendering.GammaDownsample]) with an
 * integer-offset shift search on top. An artist's sheet has a **fixed** master resolution (Brogue's tiles
 * are 128×232) and the cell is whatever the window gives, so the ratio is arbitrary — 128→14 px is
 * 9.142857 master px per output px. Hence a fractional box filter, on the CPU, here: it is Brogue's own
 * `downscaleTile` shape (fractional source rect per output pixel) rather than a halving chain.
 *
 * The box sums stay O(1) per output pixel via a per-cell **summed-area table**, sampled *bilinearly* at
 * the fractional rect corners — which is not an approximation: linear interpolation of an SAT between
 * whole-pixel entries is exactly the integral over the partial pixel, so a fractional box average is
 * exact.
 *
 * ## Two inks
 *
 * - **Coverage** ([TileInk.COVERAGE]) — the master is a single-channel mask (Brogue's route: its tiles
 *   are coverage masks tinted per cell at draw, which is why `downscaleTile` reads only one byte).
 *   Coverage is a *linear* quantity like alpha, so it is straight-averaged, exactly as the
 *   [com.sletmoe.kotile.rendering.GammaDownsample] shader averages alpha.
 * - **Full colour** ([TileInk.FULL_COLOR]) — RGB is averaged in **linear light**, weighted by alpha
 *   (i.e. premultiplied, then un-premultiplied) so colour cannot bleed out of a sprite's edge into its
 *   transparent surround, and alpha is averaged straight. That is the same arithmetic the shader does,
 *   restated on the CPU for a fractional footprint.
 *
 * All functions here are pure and GL-free (plain arrays in, plain arrays out), so the resampling maths is
 * unit-tested without a GL context — see `TileSheetResampleTest`. [TileSheetGlyphSource] owns the
 * Pixmap/Texture plumbing around them.
 */
internal object TileSheetResample {
    /**
     * Sub-pixel offsets tried per axis by the shift search, spanning one output pixel — a quarter-pixel
     * search grid (16 candidates), matching [FreeTypeGlyphSource]'s `ss / 4` step.
     */
    const val SHIFT_STEPS = 4

    /**
     * At or above this coverage (of 255) a master pixel counts as ink for the edge test below. Low enough
     * to catch a genuinely full-bleed tile whose border is antialiased, high enough that stray fringe
     * noise doesn't trip it.
     */
    const val EDGE_INK_COVERAGE = 32

    /** Rec. 601 luma weights, ×256, for reading a colour master as a single coverage channel. */
    private const val LUMA_R = 77
    private const val LUMA_G = 151
    private const val LUMA_B = 28

    /** `toLinear` for each of the 256 encoded channel values — the per-pixel `pow` is far too hot to repeat. */
    private val LINEAR = FloatArray(256) { GammaColor.toLinear(it / 255f) }

    /**
     * Reads an RGBA8888 sheet as a **coverage mask**: `coverage = luma × alpha`.
     *
     * One formula covers both conventions an artist may hand you, because the other factor is 1 in each:
     * an opaque **light-on-black** sheet (Brogue's tiles.png) carries its mask in luma with alpha
     * saturated, and a **white-on-transparent** sheet carries it in alpha with luma saturated. A
     * *dark-on-light* sheet is the one case that inverts — invert it before loading.
     */
    fun coverageMask(
        rgba: IntArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height)
        for (i in out.indices) {
            val p = rgba[i]
            val weighted =
                (p ushr 24 and 0xFF) * LUMA_R + (p ushr 16 and 0xFF) * LUMA_G + (p ushr 8 and 0xFF) * LUMA_B
            val luma = (weighted shr 8).coerceAtMost(255)
            out[i] = ((luma * (p and 0xFF)) / 255).toByte()
        }
        return out
    }

    /**
     * Which of a tile's own borders carry ink — i.e. which axes it is **full-bleed** on. A wall or floor
     * texture runs to all four edges and must meet its neighbours there; a sprite floats in a margin.
     */
    data class EdgeInk(
        /** Ink on the left or right column: the tile must not be shifted **horizontally**. */
        val sides: Boolean,
        /** Ink on the top or bottom row: the tile must not be shifted **vertically**. */
        val topBottom: Boolean,
    )

    /**
     * Measures the [srcW] x [srcH] coverage cell at ([srcX], [srcY]) for border ink, per axis.
     *
     * An axis whose border is inked sits out the shift search, for the reason krogue-9x7.4 exempted
     * box-drawing glyphs from it: a *translation* slides the sampling window past the cell edge, the clamp
     * shaves the trailing output pixel's coverage, and the seam the full-bleed art exists to close re-opens
     * as a dim line. Measured per axis rather than per tile so a tall sprite that fills its cell vertically
     * can still be snapped horizontally — and detected rather than declared, so a mixed sheet needs no
     * per-tile processing table.
     */
    fun edgeInk(
        master: ByteArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
    ): EdgeInk = edgeInk(srcW, srcH) { x, y -> master[(srcY + y) * masterW + srcX + x].toInt() and 0xFF }

    /** [edgeInk] over an RGBA master, reading each pixel's **alpha** as its coverage. */
    fun edgeInk(
        master: IntArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
    ): EdgeInk = edgeInk(srcW, srcH) { x, y -> master[(srcY + y) * masterW + srcX + x] and 0xFF }

    private inline fun edgeInk(
        srcW: Int,
        srcH: Int,
        coverageAt: (x: Int, y: Int) -> Int,
    ): EdgeInk {
        var sides = false
        var topBottom = false
        for (x in 0 until srcW) {
            if (coverageAt(x, 0) >= EDGE_INK_COVERAGE || coverageAt(x, srcH - 1) >= EDGE_INK_COVERAGE) {
                topBottom = true
                break
            }
        }
        for (y in 0 until srcH) {
            if (coverageAt(0, y) >= EDGE_INK_COVERAGE || coverageAt(srcW - 1, y) >= EDGE_INK_COVERAGE) {
                sides = true
                break
            }
        }
        return EdgeInk(sides, topBottom)
    }

    /**
     * Area-averages the [srcW] x [srcH] coverage cell at ([srcX], [srcY]) of [master] down to
     * [outW] x [outH], returning the output coverage bytes row-major.
     *
     * With [snapX] / [snapY] the cell is first aligned to the output pixel grid on that axis by the shift
     * search ([bestShift]); without them the sampling grid starts at the cell's own origin. Coverage is
     * straight-averaged (it is a linear quantity — see the class doc).
     */
    fun downscaleCoverage(
        master: ByteArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        snapX: Boolean = false,
        snapY: Boolean = false,
    ): ByteArray {
        val sat = coverageSat(master, masterW, srcX, srcY, srcW, srcH)
        val shift = bestShift(sat, srcW, srcH, outW, outH, snapX, snapY)
        val out = ByteArray(outW * outH)
        forEachBox(srcW, srcH, outW, outH, shift) { ox, oy, x0, y0, x1, y1, area ->
            val cov = satBox(sat, srcW, x0, y0, x1, y1) / area
            out[oy * outW + ox] = cov.roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * Area-averages the [srcW] x [srcH] **RGBA** cell at ([srcX], [srcY]) of [master] down to
     * [outW] x [outH], returning RGBA8888 pixels row-major.
     *
     * Colour is averaged in linear light weighted by alpha and un-premultiplied at the end; alpha is
     * averaged straight — the [com.sletmoe.kotile.rendering.GammaDownsample] arithmetic over a fractional
     * footprint. With [snapX] / [snapY] the tile is aligned to the output pixel grid by a shift search over
     * its **alpha silhouette** (the honest adaptation of a metric that assumes a coverage mask): art on a
     * transparent background aligns by its outline, while a fully opaque tile has a flat metric, so every
     * candidate ties and the search settles on no shift at all.
     */
    fun downscaleColor(
        master: IntArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        snapX: Boolean = false,
        snapY: Boolean = false,
    ): IntArray {
        val planes = colorSats(master, masterW, srcX, srcY, srcW, srcH)
        val alpha = planes[3]
        val shift = bestShift(alpha, srcW, srcH, outW, outH, snapX, snapY)
        val out = IntArray(outW * outH)
        forEachBox(srcW, srcH, outW, outH, shift) { ox, oy, x0, y0, x1, y1, area ->
            // Alpha sums in units of 255 (the SAT holds encoded alpha), colour sums are alpha-weighted
            // linear light in those same units, so the weighted mean divides one by the other.
            val aSum = satBox(alpha, srcW, x0, y0, x1, y1)
            out[oy * outW + ox] =
                if (aSum <= 0.0) {
                    0
                } else {
                    val r = encode(satBox(planes[0], srcW, x0, y0, x1, y1) / aSum)
                    val g = encode(satBox(planes[1], srcW, x0, y0, x1, y1) / aSum)
                    val b = encode(satBox(planes[2], srcW, x0, y0, x1, y1) / aSum)
                    val a = (aSum / area).roundToInt().coerceIn(0, 255)
                    (r shl 24) or (g shl 16) or (b shl 8) or a
                }
        }
        return out
    }

    /**
     * The sub-pixel offset (in master px, per axis) that best aligns this cell to the output pixel grid:
     * of the [SHIFT_STEPS]² candidates spanning one output pixel, the one minimising Brogue's blur metric
     * `Σ sin(π·coverage)` — smallest when the fewest output pixels are half-lit, i.e. when an edge lands
     * squarely on a pixel boundary rather than straddling two and reading grey.
     *
     * Same technique and metric as [FreeTypeGlyphSource]'s shift search (a re-implementation of **Brogue
     * CE**'s `optimizeTiles`/`downscaleTile` *method*, AGPL-3.0 — no Brogue code is copied; see ADR-0037),
     * generalised from that path's whole-supersample-pixel offsets to the fractional offsets an arbitrary
     * downscale ratio needs.
     *
     * [allowX] / [allowY] gate each axis: an axis the caller has ruled out (a full-bleed edge — see
     * [edgeInk]) keeps offset 0, and with both ruled out the search is skipped entirely.
     */
    fun bestShift(
        sat: DoubleArray,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        allowX: Boolean = true,
        allowY: Boolean = true,
    ): Shift {
        if (!allowX && !allowY) return ZERO_SHIFT
        val scaleX = srcW.toDouble() / outW
        val scaleY = srcH.toDouble() / outH
        var best = ZERO_SHIFT
        var bestBlur = Double.MAX_VALUE
        for (sy in 0 until if (allowY) SHIFT_STEPS else 1) {
            for (sx in 0 until if (allowX) SHIFT_STEPS else 1) {
                val shift = Shift(scaleX * sx / SHIFT_STEPS, scaleY * sy / SHIFT_STEPS)
                var blur = 0.0
                forEachBox(srcW, srcH, outW, outH, shift) { _, _, x0, y0, x1, y1, area ->
                    blur += sin(Math.PI * (satBox(sat, srcW, x0, y0, x1, y1) / area / 255.0))
                }
                if (blur < bestBlur) {
                    bestBlur = blur
                    best = shift
                }
            }
        }
        return best
    }

    /** A sub-pixel sampling offset in master pixels, per axis. */
    data class Shift(val x: Double, val y: Double)

    private val ZERO_SHIFT = Shift(0.0, 0.0)

    /**
     * Walks the output grid, handing [body] each output pixel's source box — corners already offset by
     * [shift] and clamped to the cell — plus the box's *nominal* area.
     *
     * The area is nominal (`scaleX × scaleY`) rather than the clamped rect's, deliberately and exactly as
     * [FreeTypeGlyphSource]'s emit does: a shifted window runs off the cell edge, and dividing the
     * surviving coverage by the full footprint is what makes the trailing pixel fade out like the partial
     * coverage it now is, instead of being renormalised back to solid.
     */
    private inline fun forEachBox(
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        shift: Shift,
        body: (ox: Int, oy: Int, x0: Double, y0: Double, x1: Double, y1: Double, area: Double) -> Unit,
    ) {
        val scaleX = srcW.toDouble() / outW
        val scaleY = srcH.toDouble() / outH
        val area = scaleX * scaleY
        for (oy in 0 until outH) {
            val y0 = (oy * scaleY + shift.y).coerceIn(0.0, srcH.toDouble())
            val y1 = ((oy + 1) * scaleY + shift.y).coerceIn(0.0, srcH.toDouble())
            for (ox in 0 until outW) {
                val x0 = (ox * scaleX + shift.x).coerceIn(0.0, srcW.toDouble())
                val x1 = ((ox + 1) * scaleX + shift.x).coerceIn(0.0, srcW.toDouble())
                body(ox, oy, x0, y0, x1, y1, area)
            }
        }
    }

    /** Summed-area table of one cell's coverage bytes; `(srcW + 1) x (srcH + 1)`, zero-padded row/column 0. */
    private fun coverageSat(
        master: ByteArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
    ): DoubleArray {
        val satW = srcW + 1
        val sat = DoubleArray(satW * (srcH + 1))
        for (y in 0 until srcH) {
            val src = (srcY + y) * masterW + srcX
            val row = (y + 1) * satW
            val prev = y * satW
            var runningSum = 0.0
            for (x in 0 until srcW) {
                runningSum += (master[src + x].toInt() and 0xFF).toDouble()
                sat[row + x + 1] = sat[prev + x + 1] + runningSum
            }
        }
        return sat
    }

    /**
     * Summed-area tables of one RGBA cell: `[0..2]` are the linear-light channels **premultiplied by
     * alpha** (so a transparent texel contributes no colour), `[3]` is alpha. All in units of 255, so a
     * colour SAT divided by the alpha SAT is the alpha-weighted mean in linear light.
     */
    private fun colorSats(
        master: IntArray,
        masterW: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
    ): Array<DoubleArray> {
        val satW = srcW + 1
        val sats = Array(4) { DoubleArray(satW * (srcH + 1)) }
        val running = DoubleArray(4)
        for (y in 0 until srcH) {
            val src = (srcY + y) * masterW + srcX
            val row = (y + 1) * satW
            val prev = y * satW
            running.fill(0.0)
            for (x in 0 until srcW) {
                val p = master[src + x]
                val a = (p and 0xFF).toDouble()
                running[0] += LINEAR[p ushr 24 and 0xFF] * a
                running[1] += LINEAR[p ushr 16 and 0xFF] * a
                running[2] += LINEAR[p ushr 8 and 0xFF] * a
                running[3] += a
                for (c in 0..3) {
                    sats[c][row + x + 1] = sats[c][prev + x + 1] + running[c]
                }
            }
        }
        return sats
    }

    /**
     * The integral of [sat]'s cell over the rect `([x0], [y0]) - ([x1], [y1])`, whose corners may fall
     * **between** master pixels. Bilinear interpolation of a summed-area table is not an approximation
     * here: interpolating linearly between two whole-pixel prefix sums is precisely the prefix sum at the
     * fractional position, so a partial pixel contributes exactly its covered fraction.
     */
    private fun satBox(
        sat: DoubleArray,
        srcW: Int,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Double =
        satAt(sat, srcW, x1, y1) - satAt(sat, srcW, x0, y1) -
            satAt(sat, srcW, x1, y0) + satAt(sat, srcW, x0, y0)

    private fun satAt(
        sat: DoubleArray,
        srcW: Int,
        x: Double,
        y: Double,
    ): Double {
        val satW = srcW + 1
        val height = sat.size / satW - 1
        val xc = x.coerceIn(0.0, srcW.toDouble())
        val yc = y.coerceIn(0.0, height.toDouble())
        val xi = xc.toInt().coerceAtMost(srcW - 1)
        val yi = yc.toInt().coerceAtMost(height - 1)
        val fx = xc - xi
        val fy = yc - yi
        val top = yi * satW + xi
        val bottom = top + satW
        val above = sat[top] + (sat[top + 1] - sat[top]) * fx
        val below = sat[bottom] + (sat[bottom + 1] - sat[bottom]) * fx
        return above + (below - above) * fy
    }

    /** Re-encodes a linear-light channel value in `[0, 1]` back to an sRGB byte. */
    private fun encode(linear: Double): Int {
        val srgb = GammaColor.toSrgb(linear.toFloat().coerceIn(0f, 1f))
        return (srgb * 255f).roundToInt().coerceIn(0, 255)
    }
}
