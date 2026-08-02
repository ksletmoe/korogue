package com.sletmoe.kotile.display.ascii

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the tilesheet resampler behind [TileSheetGlyphSource] (krogue-9x7.7,
 * ADR-0043) — the fractional box downscale, the linear-light colour path, and the pixel-grid shift
 * search. These are plain arrays in and out, so unlike the glyph-source integration specs they run
 * everywhere, including macOS.
 */
class TileSheetResampleTest : FunSpec({

    /** Coverage of the output pixel at ([x], [y]) of a [w]-wide result. */
    fun ByteArray.coverage(
        x: Int,
        y: Int,
        w: Int,
    ): Int = this[y * w + x].toInt() and 0xFF

    fun rgba(
        r: Int,
        g: Int,
        b: Int,
        a: Int,
    ): Int = (r shl 24) or (g shl 16) or (b shl 8) or a

    context("reading a sheet as a coverage mask") {
        test("light-on-black and white-on-transparent sheets read identically") {
            // The two conventions an artist may hand you. coverage = luma x alpha, and the other factor
            // saturates in each case, so a half-covered pixel is 128-ish either way.
            val onBlack = intArrayOf(rgba(255, 255, 255, 255), rgba(128, 128, 128, 255), rgba(0, 0, 0, 255))
            val onTransparent = intArrayOf(rgba(255, 255, 255, 255), rgba(255, 255, 255, 128), rgba(0, 0, 0, 0))

            val fromBlack = TileSheetResample.coverageMask(onBlack, 3, 1)
            val fromTransparent = TileSheetResample.coverageMask(onTransparent, 3, 1)

            fromBlack.map { it.toInt() and 0xFF } shouldBe listOf(255, 128, 0)
            fromTransparent.map { it.toInt() and 0xFF } shouldBe listOf(255, 128, 0)
        }

        test("colour is collapsed by luma, so a saturated tile still carries a mask") {
            // Pure red is dark by Rec. 601 luma (~0.30) -- a colour sheet read as COVERAGE loses its hue,
            // which is exactly the trade TileInk.COVERAGE documents.
            val red = TileSheetResample.coverageMask(intArrayOf(rgba(255, 0, 0, 255)), 1, 1)
            red.coverage(0, 0, 1) shouldBe 76
        }
    }

    context("the fractional box downscale") {
        test("a non-integer ratio area-weights the partial master pixels") {
            // 3 master px -> 2 output px, i.e. 1.5 master px each. Output 0 covers master [0, 1.5) =
            // 255 + half of 255, over a footprint of 1.5 -> 255. Output 1 covers [1.5, 3) = half of 255
            // plus a blank -> 85. A downscale that rounded the box to whole master pixels could not
            // produce 85; nearest-neighbour would give 255 and 0.
            val master = byteArrayOf(255.toByte(), 255.toByte(), 0)

            val out = TileSheetResample.downscaleCoverage(master, 3, 0, 0, 3, 1, outW = 2, outH = 1)

            out.coverage(0, 0, 2) shouldBe 255
            out.coverage(1, 0, 2) shouldBe 85
        }

        test("coverage is straight-averaged, not gamma-averaged") {
            // Coverage is a linear quantity (how MUCH ink), like alpha -- so half ink, half blank is 128.
            // Gamma-averaging it, as the colour path does for RGB, would land near 188 and make every
            // mask edge too bright. This is the ADR-0043 decision, pinned.
            val master = byteArrayOf(255.toByte(), 0)

            val out = TileSheetResample.downscaleCoverage(master, 2, 0, 0, 2, 1, outW = 1, outH = 1)

            out.coverage(0, 0, 1) shouldBe 128
        }

        test("total ink is conserved across an awkward Brogue-scale ratio") {
            // 128x232 master (Brogue's tile size) into a 14x22 cell: 9.14 x 10.5 master px per output px.
            // Area-averaging must neither invent nor lose ink, so the mean coverage survives the shrink.
            val mw = 128
            val mh = 232
            val master = ByteArray(mw * mh)
            for (y in 40 until 190) {
                for (x in 30 until 100) {
                    master[y * mw + x] = 255.toByte()
                }
            }
            val masterMean = master.sumOf { it.toInt() and 0xFF }.toDouble() / (mw * mh)

            val out = TileSheetResample.downscaleCoverage(master, mw, 0, 0, mw, mh, outW = 14, outH = 22)

            val outMean = out.sumOf { it.toInt() and 0xFF }.toDouble() / out.size
            outMean shouldBe (masterMean plusOrMinus 1.0)
        }

        test("a cell is sampled from its own rect, not its neighbours'") {
            // Two tiles side by side in one 4x1 master row: an inked tile then a blank one.
            val master = byteArrayOf(255.toByte(), 255.toByte(), 0, 0)

            val inked = TileSheetResample.downscaleCoverage(master, 4, 0, 0, 2, 1, outW = 1, outH = 1)
            val blank = TileSheetResample.downscaleCoverage(master, 4, 2, 0, 2, 1, outW = 1, outH = 1)

            inked.coverage(0, 0, 1) shouldBe 255
            blank.coverage(0, 0, 1) shouldBe 0
        }
    }

    context("the full-colour path") {
        test("RGB is averaged in linear light, so a black/white edge lands at ~188 not 128") {
            // The whole point of the gamma-correct downsample (ADR-0036 tier 2), restated on the CPU for a
            // fractional footprint: the encoded-space midpoint 128 is the muddy, wrong answer.
            val master = intArrayOf(rgba(255, 255, 255, 255), rgba(0, 0, 0, 255))

            val out = TileSheetResample.downscaleColor(master, 2, 0, 0, 2, 1, outW = 1, outH = 1)

            (out[0] ushr 24 and 0xFF) shouldBe 188
            (out[0] and 0xFF) shouldBe 255
        }

        test("colour is alpha-weighted, so a transparent neighbour cannot bleed into an edge") {
            // Opaque red beside a fully transparent GREEN texel. Weighted by alpha the green contributes
            // nothing and the edge stays red at half alpha; an unweighted average would tint it.
            val master = intArrayOf(rgba(255, 0, 0, 255), rgba(0, 255, 0, 0))

            val out = TileSheetResample.downscaleColor(master, 2, 0, 0, 2, 1, outW = 1, outH = 1)

            withClue("RGB should be untouched red") {
                (out[0] ushr 24 and 0xFF) shouldBe 255
                (out[0] ushr 16 and 0xFF) shouldBe 0
                (out[0] ushr 8 and 0xFF) shouldBe 0
            }
            (out[0] and 0xFF) shouldBe 128
        }

        test("a fully transparent tile downscales to nothing") {
            val master = IntArray(4) { rgba(255, 255, 255, 0) }

            val out = TileSheetResample.downscaleColor(master, 2, 0, 0, 2, 2, outW = 1, outH = 1)

            out[0] shouldBe 0
        }
    }

    context("the pixel-grid shift search") {
        // A 4-master-px bar starting at x=2 in a 16x4 cell downscaled 4:1. Unshifted it straddles the
        // boundary between output pixels 0 and 1 (each half-lit); a 2-master-px shift lands it squarely on
        // output pixel 0. That is precisely what the blur metric is for.
        fun straddlingBar(): ByteArray {
            val master = ByteArray(16 * 4)
            for (y in 0 until 4) {
                for (x in 2 until 6) {
                    master[y * 16 + x] = 255.toByte()
                }
            }
            return master
        }

        test("snapping turns two half-lit output pixels into one solid one") {
            val master = straddlingBar()

            val loose = TileSheetResample.downscaleCoverage(master, 16, 0, 0, 16, 4, outW = 4, outH = 4)
            val snapped =
                TileSheetResample.downscaleCoverage(
                    master,
                    16,
                    0,
                    0,
                    16,
                    4,
                    outW = 4,
                    outH = 4,
                    snapX = true,
                    snapY = true,
                )

            withClue("unsnapped: the bar is split across two grey columns") {
                loose.coverage(0, 0, 4) shouldBe 128
                loose.coverage(1, 0, 4) shouldBe 128
            }
            withClue("snapped: the bar occupies one column at full ink") {
                snapped.coverage(0, 0, 4) shouldBe 255
                snapped.coverage(1, 0, 4) shouldBe 0
            }
        }

        test("the search is off by default, so the unshifted grid is what a plain downscale gives") {
            val master = straddlingBar()

            val default = TileSheetResample.downscaleCoverage(master, 16, 0, 0, 16, 4, outW = 4, outH = 4)
            val explicitlyLoose =
                TileSheetResample.downscaleCoverage(
                    master,
                    16,
                    0,
                    0,
                    16,
                    4,
                    outW = 4,
                    outH = 4,
                    snapX = false,
                    snapY = false,
                )

            default.toList() shouldBe explicitlyLoose.toList()
        }

        test("a gated axis keeps offset zero while the other still snaps") {
            val master = straddlingBar()

            val xOnly =
                TileSheetResample.downscaleCoverage(
                    master,
                    16,
                    0,
                    0,
                    16,
                    4,
                    outW = 4,
                    outH = 4,
                    snapX = true,
                    snapY = false,
                )

            // The bar is uniform vertically, so gating Y costs nothing: X alone still resolves it.
            xOnly.coverage(0, 0, 4) shouldBe 255
        }

        test("snapping reduces the blur metric it optimises") {
            val master = straddlingBar()
            val loose = TileSheetResample.downscaleCoverage(master, 16, 0, 0, 16, 4, outW = 4, outH = 4)
            val snapped =
                TileSheetResample.downscaleCoverage(
                    master,
                    16,
                    0,
                    0,
                    16,
                    4,
                    outW = 4,
                    outH = 4,
                    snapX = true,
                    snapY = true,
                )

            // Brogue's metric: half-lit pixels are the expensive ones. Count them as a proxy.
            fun greyPixels(tile: ByteArray) = tile.count { (it.toInt() and 0xFF) in 1..254 }

            greyPixels(snapped) shouldBeLessThan greyPixels(loose)
            greyPixels(snapped) shouldBe 0
        }
    }

    context("the full-bleed edge exemption") {
        test("a tile whose art runs to every border is flagged on both axes") {
            val master = ByteArray(8 * 8) { 255.toByte() }

            val edge = TileSheetResample.edgeInk(master, 8, 0, 0, 8, 8)

            edge shouldBe TileSheetResample.EdgeInk(sides = true, topBottom = true)
        }

        test("a figure floating in a margin is flagged on neither") {
            val master = ByteArray(8 * 8)
            for (y in 2 until 6) {
                for (x in 2 until 6) {
                    master[y * 8 + x] = 255.toByte()
                }
            }

            TileSheetResample.edgeInk(master, 8, 0, 0, 8, 8) shouldBe
                TileSheetResample.EdgeInk(sides = false, topBottom = false)
        }

        test("a tile that bleeds off only one axis is flagged only there") {
            // A full-height column inset from the left and right edges: vertical is full-bleed, horizontal
            // is not -- so it may still be snapped horizontally.
            val master = ByteArray(8 * 8)
            for (y in 0 until 8) {
                for (x in 3 until 5) {
                    master[y * 8 + x] = 255.toByte()
                }
            }

            TileSheetResample.edgeInk(master, 8, 0, 0, 8, 8) shouldBe
                TileSheetResample.EdgeInk(sides = false, topBottom = true)
        }

        test("an RGBA master is measured by its alpha silhouette") {
            // Opaque black art (luma 0) still counts as ink -- the colour path must read alpha, not luma,
            // or every dark sprite would be mistaken for an empty margin.
            val master = IntArray(4 * 4) { rgba(0, 0, 0, 255) }

            TileSheetResample.edgeInk(master, 4, 0, 0, 4, 4) shouldBe
                TileSheetResample.EdgeInk(sides = true, topBottom = true)
        }

        test("snapping a full-bleed tile shaves its trailing edge -- which is why it is exempted") {
            // The damage the exemption exists to prevent, shown directly. A wall-like tile: inked to every
            // border, with a groove at x=[1,5) that the search wants to align by shifting one master px.
            // Doing so slides the sampling window off the right edge, and the last output column -- the one
            // that has to meet the neighbouring cell -- fades from solid to grey. That is the seam.
            val master = ByteArray(16 * 4) { 255.toByte() }
            for (y in 0 until 4) {
                for (x in 1 until 5) {
                    master[y * 16 + x] = 0
                }
            }

            val exempt = TileSheetResample.downscaleCoverage(master, 16, 0, 0, 16, 4, outW = 4, outH = 4)
            val forced =
                TileSheetResample.downscaleCoverage(
                    master,
                    16,
                    0,
                    0,
                    16,
                    4,
                    outW = 4,
                    outH = 4,
                    snapX = true,
                    snapY = true,
                )

            withClue("this tile is full-bleed on both axes, so the source never lets it snap") {
                TileSheetResample.edgeInk(master, 16, 0, 0, 16, 4) shouldBe
                    TileSheetResample.EdgeInk(sides = true, topBottom = true)
            }
            exempt.coverage(3, 0, 4) shouldBe 255
            forced.coverage(3, 0, 4) shouldBeLessThan 255
        }
    }

    context("degenerate ratios") {
        test("an upscale falls back to a nearest-neighbour-like read rather than failing") {
            // Documented behaviour, not a recommendation: each output pixel's footprint is then under one
            // master pixel, so it reads that pixel's value.
            val master = byteArrayOf(255.toByte(), 0)

            val out = TileSheetResample.downscaleCoverage(master, 2, 0, 0, 2, 1, outW = 4, outH = 1)

            out.coverage(0, 0, 4) shouldBe 255
            out.coverage(1, 0, 4) shouldBe 255
            out.coverage(3, 0, 4) shouldBe 0
        }

        test("a whole tile collapsing to a single pixel averages all of it") {
            val master = ByteArray(10 * 10) { if (it < 50) 255.toByte() else 0 }

            val out = TileSheetResample.downscaleCoverage(master, 10, 0, 0, 10, 10, outW = 1, outH = 1)

            out.coverage(0, 0, 1) shouldBe 128
        }
    }
})
