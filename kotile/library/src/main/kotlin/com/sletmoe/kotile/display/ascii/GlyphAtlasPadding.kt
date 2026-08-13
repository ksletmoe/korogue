package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Pixmap

/**
 * The gutter that separates the cells of a glyph atlas page, and the packing that fills it (krogue-wcw,
 * ADR-0044).
 *
 * ## Why a page needs one
 *
 * [FreeTypeGlyphSource] and [TileSheetGlyphSource] both publish their cells as [regions][
 * com.badlogic.gdx.graphics.g2d.TextureRegion] of one `Linear`-filtered page. Drawn at any scale other
 * than 1:1 — the `FitScale` / `fitToWindow` / sharp-bilinear paths — the GPU's bilinear unit samples up to
 * **half a texel** beyond the region's outer edge, which in a tightly packed page is the *neighbouring*
 * cell. At a magnifying scale that is not a rounding error: the outermost drawn pixel column samples
 * `0.5 - 0.5/scale` of a texel past the edge, so at 8× it carries ~44% of the neighbour's ink.
 *
 * This was invisible while every glyph was ink-centred and stopped short of its cell edges. Since
 * ADR-0040 the cell-filling class (box drawing and blocks) is edge-snapped and *does* reach the cell edge,
 * and an artist tile is full-bleed by design (ADR-0043) — so both pages now hold cells whose ink runs to
 * the border, and both can bleed into each other.
 *
 * ## What it does
 *
 * Each cell is packed with a [GUTTER_PX]-texel border, filled by **extruding the cell's own edge row and
 * column** into it rather than leaving it transparent. That makes the sample that reaches past the region
 * edge read the edge texel again — the behaviour `CLAMP_TO_EDGE` gives a whole texture, emulated at each
 * *cell* boundary. (The wrap mode itself is per texture object, so on an atlas it only guards the page's
 * outer border.) No per-cell textures, no shader.
 * Extruding rather than clearing is the point: a transparent gutter would fix the bleed by fading a
 * full-bleed tile out at its border, re-opening the very seam ADR-0040/0043 close.
 *
 * One texel is enough because bilinear reach is half a texel and these pages carry no mipmaps (a mipmapped
 * page would need a gutter per level). The cost is `2 × GUTTER_PX` px per cell per axis of page size —
 * 32 px on a 16×16 CP437 page — and one cell-resolution pixmap copy per rasterise.
 */
internal object GlyphAtlasPadding {
    /** Width of the extruded border around every cell, in texels. See the object doc for why it is 1. */
    const val GUTTER_PX = 1

    /** The page span, in px, holding [count] cells of [cellPx] each with their gutters. */
    fun pageSpanPx(
        cellPx: Int,
        count: Int,
    ): Int = count * (cellPx + 2 * GUTTER_PX)

    /** Where cell [index] starts in a padded page of [cellPx] cells — the origin of its texture region. */
    fun cellOriginPx(
        index: Int,
        cellPx: Int,
    ): Int = index * (cellPx + 2 * GUTTER_PX) + GUTTER_PX

    /**
     * Repacks the tightly-packed [tight] page — a [columns] x [rows] grid of [cellWidthPx] x
     * [cellHeightPx] cells — into a new page with an extruded gutter around every cell. The caller owns
     * the returned pixmap and keeps ownership of [tight].
     *
     * The page is straight-alpha (white RGB, coverage in alpha, or full-colour tile art), so blending is
     * off throughout: every copy here must reproduce the source texel verbatim, including a transparent
     * one, rather than compositing it over the cleared page.
     */
    fun padded(
        tight: Pixmap,
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): Pixmap {
        require(tight.width == columns * cellWidthPx && tight.height == rows * cellHeightPx) {
            "page ${tight.width}x${tight.height} is not a ${columns}x$rows grid of " +
                "${cellWidthPx}x$cellHeightPx cells"
        }
        val page =
            Pixmap(
                pageSpanPx(cellWidthPx, columns),
                pageSpanPx(cellHeightPx, rows),
                Pixmap.Format.RGBA8888,
            ).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
            }
        try {
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    extrudeCell(
                        page,
                        tight,
                        srcX = col * cellWidthPx,
                        srcY = row * cellHeightPx,
                        dstX = cellOriginPx(col, cellWidthPx),
                        dstY = cellOriginPx(row, cellHeightPx),
                        cellWidthPx = cellWidthPx,
                        cellHeightPx = cellHeightPx,
                    )
                }
            }
        } catch (t: Throwable) {
            page.dispose() // nobody owns it yet; don't leak it on the way out
            throw t
        }
        return page
    }

    /**
     * Copies one cell from [tight] at ([srcX], [srcY]) to [page] at ([dstX], [dstY]) and replicates its
     * border row/column/corner texels into the surrounding gutter. Written as 1-texel strips because
     * [GUTTER_PX] is 1; a wider gutter would want a scaled blit instead.
     */
    private fun extrudeCell(
        page: Pixmap,
        tight: Pixmap,
        srcX: Int,
        srcY: Int,
        dstX: Int,
        dstY: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ) {
        val lastX = srcX + cellWidthPx - 1
        val lastY = srcY + cellHeightPx - 1
        val rightX = dstX + cellWidthPx
        val bottomY = dstY + cellHeightPx

        page.drawPixmap(tight, dstX, dstY, srcX, srcY, cellWidthPx, cellHeightPx)

        page.drawPixmap(tight, dstX - GUTTER_PX, dstY, srcX, srcY, 1, cellHeightPx) // left edge
        page.drawPixmap(tight, rightX, dstY, lastX, srcY, 1, cellHeightPx) // right edge
        page.drawPixmap(tight, dstX, dstY - GUTTER_PX, srcX, srcY, cellWidthPx, 1) // top edge
        page.drawPixmap(tight, dstX, bottomY, srcX, lastY, cellWidthPx, 1) // bottom edge

        // The corners: reached by a diagonal bilinear sample at the cell's corner pixel, so they carry the
        // corner texel rather than staying clear.
        page.drawPixmap(tight, dstX - GUTTER_PX, dstY - GUTTER_PX, srcX, srcY, 1, 1)
        page.drawPixmap(tight, rightX, dstY - GUTTER_PX, lastX, srcY, 1, 1)
        page.drawPixmap(tight, dstX - GUTTER_PX, bottomY, srcX, lastY, 1, 1)
        page.drawPixmap(tight, rightX, bottomY, lastX, lastY, 1, 1)
    }
}
