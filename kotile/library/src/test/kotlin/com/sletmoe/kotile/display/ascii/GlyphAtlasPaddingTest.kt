package com.sletmoe.kotile.display.ascii

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the atlas page geometry behind [GlyphAtlasPadding] (krogue-wcw, ADR-0044).
 * The extrusion itself needs a `Pixmap` (native, GL-adjacent) and is covered by the magnified-draw GL
 * specs; the arithmetic the `GL_MAX_TEXTURE_SIZE` guards depend on is plain integer maths, so unlike
 * those specs it runs everywhere, including macOS.
 */
class GlyphAtlasPaddingTest : FunSpec({

    test("page span and cell origins account for a gutter on BOTH sides of every cell") {
        // A 16x16 CP437 page of 24px cells: each cell occupies 24 + 2*1, so the page is 16*26 = 416 —
        // the tight 384 plus one gutter per cell edge. Getting this wrong by a factor (one gutter per
        // cell rather than two) still yields a plausible-looking page, so pin the number.
        GlyphAtlasPadding.pageSpanPx(24, 16) shouldBe 416L
        // Cell 0 starts one gutter in, not at 0 — the page opens with a gutter, and each cell's origin
        // is its own stride further along.
        GlyphAtlasPadding.cellOriginPx(0, 24) shouldBe 1
        GlyphAtlasPadding.cellOriginPx(1, 24) shouldBe 27
        GlyphAtlasPadding.cellOriginPx(15, 24) shouldBe 391
        withClue("the last cell plus its trailing gutter must land exactly on the page edge") {
            (GlyphAtlasPadding.cellOriginPx(15, 24) + 24 + GlyphAtlasPadding.GUTTER_PX) shouldBe 416
        }
    }

    test("a non-square cell pads each axis independently") {
        GlyphAtlasPadding.pageSpanPx(9, 16) shouldBe 176L // 16 * (9 + 2)
        GlyphAtlasPadding.pageSpanPx(16, 16) shouldBe 288L // 16 * (16 + 2)
        GlyphAtlasPadding.cellOriginPx(3, 9) shouldBe 34 // 3 * 11 + 1
    }

    test("the span stays exact past Int range, so an oversized page cannot slip under a size limit") {
        // The span feeds the GL_MAX_TEXTURE_SIZE check in both sources. Computed in Int, a cell size
        // this large wraps NEGATIVE and would pass a `<= maxTexture` test — letting through exactly the
        // page the guard exists to reject. In Long it stays enormous and the guard fires.
        val absurd = GlyphAtlasPadding.pageSpanPx(Int.MAX_VALUE, 16)
        absurd shouldBeGreaterThan Int.MAX_VALUE.toLong()
        withClue("the same product in Int arithmetic is what the guard must not be handed") {
            (16 * (Int.MAX_VALUE + 2 * GlyphAtlasPadding.GUTTER_PX)) shouldBe 16 // wraps
        }
        // A realistic ceiling case: a 4096px cell on a 16-column page is 65_568 px, over every current
        // GL_MAX_TEXTURE_SIZE, and reads as such rather than as something small.
        GlyphAtlasPadding.pageSpanPx(4096, 16) shouldBe 65_568L
    }
})
