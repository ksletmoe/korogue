package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.BufferUtils
import kotlin.math.roundToInt

/**
 * How a [TileSheetGlyphSource] reads its sheet — the single most consequential choice about an artist
 * tilesheet, because it decides whether the tiles can still be **tinted per cell** (ADR-0043).
 */
enum class TileInk {
    /**
     * The sheet is a **coverage mask** (Brogue's route): its pixels say *how much* ink, not *what colour*
     * — light-on-black or white-on-transparent, either read the same (see
     * [TileSheetResample.coverageMask]). The atlas is built as white RGB with coverage in alpha, so a tile
     * takes the cell's foreground colour exactly like a [Font] glyph does, and one silhouette serves every
     * palette entry. The default, and the only ink the full crispness pipeline applies to.
     */
    COVERAGE,

    /**
     * The sheet is **full-colour art**: RGB is carried through the downscale (in linear light, alpha
     * weighted) instead of being collapsed to coverage. Bespoke sprite art, at the cost of the tint: the
     * cell's foreground colour still *multiplies* the tile, so a consumer wanting the art as drawn must
     * paint those cells white. Pixel-grid alignment is weaker here too — see [TileSheetGlyphSource].
     */
    FULL_COLOR,
}

/**
 * How a [TileSheetGlyphSource] fits a master tile whose aspect differs from the on-screen cell's — the
 * coarse stand-in for Brogue's per-tile processing table (`TileProcessing`'s stretch/fit flags).
 */
enum class TileScaling {
    /**
     * Fill the cell, distorting the tile if the aspects differ. Right for terrain and walls, which must
     * meet their neighbours edge to edge — a letterboxed wall tile would show gaps. The default.
     */
    STRETCH,

    /**
     * Preserve the master tile's aspect, centring it in the cell and leaving the remainder transparent.
     * Right for figures — a monster squashed to a square cell reads as a mistake — and wrong for anything
     * that has to tile seamlessly.
     */
    PRESERVE_ASPECT,
}

/**
 * A [GlyphSource] backed by a **high-resolution artist tilesheet**, downscaled to the on-screen cell size
 * — the third way to author cells, alongside the fixed bitmap [Font] and the vector [FreeTypeGlyphSource]
 * (ADR-0043, krogue-9x7.7). It is the route **Brogue** takes: one PNG of hand-drawn tiles at far above
 * display resolution (Brogue's are 128×232 each), resolved down per cell so the art stays crisp at any
 * window size without ever being authored per size.
 *
 * Use it when the cell wants a *drawn* shape a font cannot give you — a textured wall, a monster
 * silhouette, a bespoke item — while keeping the rest of the grid pipeline (tinting, layering, the ASCII
 * window) unchanged.
 *
 * ## Addressing
 *
 * Cells are indexed **row-major by `char.code`**, top-left first, exactly as the bitmap [Font] indexes its
 * code page: `glyph(Char(0))` is the sheet's first tile. A sheet laid out as a CP437 page is therefore a
 * drop-in replacement for a [Font]; a sheet with more than 256 tiles is addressable past the code page
 * (`Char(300)`), and a code beyond the last tile yields `null`. There is no CP437→Unicode mapping to do —
 * the sheet *is* the mapping.
 *
 * ## How the downscale works
 *
 * On construction and on every changed [prepareForCellSize], each master tile is area-averaged down to the
 * cell size by [TileSheetResample]: a **fractional box filter** (the ratio is arbitrary — a 128px master
 * into a 14px cell is 9.14 master px per output px), summed-area-table backed so each output pixel costs
 * O(1). [TileInk.COVERAGE] averages coverage straight, as the linear quantity it is; [TileInk.FULL_COLOR]
 * averages RGB in **linear light** weighted by alpha, which is the same arithmetic tier 2's
 * [com.sletmoe.kotile.rendering.GammaDownsample] shader does — and the reason edges keep their brightness
 * instead of going muddy.
 *
 * With [snapToPixelGrid] each tile is additionally aligned to the output pixel grid by the sub-pixel
 * **shift search** of ADR-0037 (Brogue's `optimizeTiles` technique), so an edge lands on a pixel boundary
 * rather than straddling two. Two caveats, both from the metric assuming a coverage mask:
 *
 * - A **full-bleed** edge (ink on the tile's own border — walls, floor textures) is detected and sits out
 *   the search **on that axis**, because translating it would shave the trailing edge and re-open the seam
 *   it exists to close (the same exemption box-drawing glyphs get in [FreeTypeGlyphSource], krogue-9x7.4).
 *   Per axis, so a figure that fills its cell vertically can still be snapped horizontally.
 * - Under [TileInk.FULL_COLOR] the search runs on the tile's **alpha silhouette**. Art on a transparent
 *   background aligns by its outline; a fully **opaque** colour tile has a flat metric and the search is a
 *   no-op — for that art, either author it with transparency or leave snapping off.
 *
 * ## When *not* to use this
 *
 * Full-colour pixel art authored at (or near) the final cell size is better served by the tier-1 route: a
 * [Font]-style sheet at native size with an integer scale, which reproduces the artist's pixels exactly
 * rather than resolving a master down to them. This source is for masters **well above** cell resolution;
 * asking it to *upscale* (a cell larger than the master tile) degenerates to nearest-neighbour, since each
 * output pixel's footprint is then under one master pixel.
 *
 * ## Cost and lifetime
 *
 * The whole sheet is downscaled on the CPU per rasterise — construction, and each changed
 * [prepareForCellSize] — so cost scales with the master's total pixel count, and [snapToPixelGrid]
 * multiplies the per-tile work by the search grid. A large sheet on a resize-heavy path is exactly the
 * case to leave snapping off (or to keep a fixed cell size). The master pixels are held in memory for the
 * life of the source so resizes need no re-read; a coverage sheet keeps one byte per master pixel, a
 * full-colour sheet four. Owns a GPU texture; [dispose] releases it and the master.
 *
 * The atlas is one cell-resolution page of the whole sheet, each tile packed with the one-texel extruded
 * gutter of [GlyphAtlasPadding] (ADR-0044) so a full-bleed tile drawn at a magnifying scale cannot sample
 * its neighbour's ink — the same packing [FreeTypeGlyphSource] uses. A large grid at a large cell can
 * still exceed `GL_MAX_TEXTURE_SIZE`; that is checked and reported rather than left to render as garbage.
 *
 * @param sheet the tilesheet PNG (any format libGDX can read; converted to RGBA8888 on load). Read once at
 *   construction — the caller may free the handle after.
 * @param columns tiles across the sheet; must divide the sheet width exactly
 * @param rows tiles down the sheet; must divide the sheet height exactly
 * @param cellWidthPx initial on-screen cell width in pixels
 * @param cellHeightPx initial on-screen cell height in pixels
 * @param ink how the sheet's pixels are read — a tintable coverage mask (default) or full colour. See
 *   [TileInk]; this is the choice to make deliberately.
 * @param scaling how a master tile whose aspect differs from the cell's is fitted. See [TileScaling].
 * @param snapToPixelGrid align each tile to the output pixel grid with the ADR-0037 shift search. Off by
 *   default: it costs a search per tile per rasterise, and the caveats above apply.
 */
class TileSheetGlyphSource(
    sheet: FileHandle,
    private val columns: Int,
    private val rows: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
    private val ink: TileInk = TileInk.COVERAGE,
    private val scaling: TileScaling = TileScaling.STRETCH,
    private val snapToPixelGrid: Boolean = false,
) : GlyphSource {
    /** Width in pixels of one tile as authored in the sheet — the resolution the downscale resolves from. */
    val masterTileWidthPx: Int

    /** Height in pixels of one tile as authored in the sheet. */
    val masterTileHeightPx: Int

    /** How many tiles the sheet holds; `char.code` at or above this yields no glyph. */
    val tileCount: Int get() = columns * rows

    // The master pixels, kept for the life of the source so a resize re-downscales without re-reading the
    // file. Exactly one is populated, per `ink`: coverage is one byte per master pixel, colour is a packed
    // RGBA8888 int. Nulled by dispose().
    private var coverageMaster: ByteArray? = null
    private var colorMaster: IntArray? = null

    private val masterWidth: Int

    override var charWidthPx: Int = cellWidthPx.coerceAtLeast(1)
        private set

    override var charHeightPx: Int = cellHeightPx.coerceAtLeast(1)
        private set

    // The current atlas texture and its per-tile regions; replaced wholesale on each rasterise.
    private var atlas: Texture? = null
    private var regions: List<TextureRegion> = emptyList()

    // Scratch for the GL_MAX_TEXTURE_SIZE query in [rasterize]; reused rather than allocated per resize.
    private val glQueryBuffer = BufferUtils.newIntBuffer(16)

    init {
        require(columns >= 1 && rows >= 1) { "tilesheet needs at least one column and row, got ${columns}x$rows" }
        val loaded = load(sheet)
        masterWidth = loaded.width
        masterTileWidthPx = loaded.width / columns
        masterTileHeightPx = loaded.height / rows
        when (ink) {
            TileInk.COVERAGE ->
                coverageMaster = TileSheetResample.coverageMask(loaded.rgba, loaded.width, loaded.height)
            TileInk.FULL_COLOR -> colorMaster = loaded.rgba
        }
        var ok = false
        try {
            rasterize(charWidthPx, charHeightPx)
            ok = true
        } finally {
            // Nothing the caller can reach holds a dispose() for this half-built object.
            if (!ok) dispose()
        }
    }

    override fun glyph(character: Char): TextureRegion? = regions.getOrNull(character.code)

    /**
     * Re-downscales the whole sheet for a [widthPx] x [heightPx] cell. An unchanged size is a no-op; any
     * other size rebuilds the atlas from the master pixels (see the class doc on cost).
     */
    override fun prepareForCellSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        if (w == charWidthPx && h == charHeightPx && atlas != null) return
        rasterize(w, h)
    }

    /**
     * Rebuilds the tile atlas at a [w] x [h] cell size: every master tile is area-averaged down (and, under
     * [snapToPixelGrid], shift-aligned) into its own cell of one RGBA8888 pixmap, which is uploaded as the
     * new atlas texture. Needs a GL context for that upload only — the resampling is pure CPU.
     */
    private fun rasterize(
        w: Int,
        h: Int,
    ) {
        // The atlas is one cell-resolution page of the whole sheet, so a large grid at a large cell can
        // outgrow what the GPU will allocate. That failure is otherwise silent — an over-sized texture
        // renders as garbage — so say plainly what exceeded what, and what the consumer can change.
        val maxTexture = maxTextureSize()
        // Measured on the PADDED page, since that is what gets uploaded (the per-cell gutter costs two px
        // per cell per axis) — checking the tight size would pass a page the GPU then refuses.
        val pageWidth = GlyphAtlasPadding.pageSpanPx(w, columns)
        val pageHeight = GlyphAtlasPadding.pageSpanPx(h, rows)
        check(pageWidth <= maxTexture && pageHeight <= maxTexture) {
            "tilesheet atlas ${pageWidth}x$pageHeight exceeds GL_MAX_TEXTURE_SIZE ($maxTexture) at a " +
                "${w}x$h cell; use a smaller cell, or split the ${columns}x$rows sheet across sources"
        }
        val fit = fitRect(w, h)
        val page =
            Pixmap(columns * w, rows * h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
            }
        try {
            for (slot in 0 until tileCount) {
                val srcX = (slot % columns) * masterTileWidthPx
                val srcY = (slot / columns) * masterTileHeightPx
                val destX = (slot % columns) * w + fit.offsetX
                val destY = (slot / columns) * h + fit.offsetY
                when (ink) {
                    TileInk.COVERAGE -> emitCoverageTile(page, srcX, srcY, destX, destY, fit)
                    TileInk.FULL_COLOR -> emitColorTile(page, srcX, srcY, destX, destY, fit)
                }
            }
            // Repack with an extruded gutter around every tile before upload (krogue-wcw, ADR-0044): the
            // page is Linear-filtered, so a full-bleed tile drawn at a magnifying scale would otherwise
            // sample its neighbour's ink half a texel past its own edge.
            val padded = GlyphAtlasPadding.padded(page, columns, rows, w, h)
            val newAtlas =
                try {
                    Texture(padded).apply {
                        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                    }
                } finally {
                    padded.dispose()
                }
            atlas?.dispose()
            atlas = newAtlas
            regions =
                (0 until tileCount).map { slot ->
                    TextureRegion(
                        newAtlas,
                        GlyphAtlasPadding.cellOriginPx(slot % columns, w),
                        GlyphAtlasPadding.cellOriginPx(slot / columns, h),
                        w,
                        h,
                    )
                }
        } finally {
            page.dispose()
        }
        charWidthPx = w
        charHeightPx = h
    }

    /** Downscales one master tile as a coverage mask and writes it as white RGB with coverage in alpha. */
    private fun emitCoverageTile(
        page: Pixmap,
        srcX: Int,
        srcY: Int,
        destX: Int,
        destY: Int,
        fit: FitRect,
    ) {
        val master = requireNotNull(coverageMaster) { "TileSheetGlyphSource has been disposed" }
        // An axis this tile bleeds off must not be translated (see the class doc); it keeps the unshifted grid.
        val edge = TileSheetResample.edgeInk(master, masterWidth, srcX, srcY, masterTileWidthPx, masterTileHeightPx)
        val tile =
            TileSheetResample.downscaleCoverage(
                master,
                masterWidth,
                srcX,
                srcY,
                masterTileWidthPx,
                masterTileHeightPx,
                fit.width,
                fit.height,
                snapX = snapToPixelGrid && !edge.sides,
                snapY = snapToPixelGrid && !edge.topBottom,
            )
        for (y in 0 until fit.height) {
            for (x in 0 until fit.width) {
                val coverage = tile[y * fit.width + x].toInt() and 0xFF
                if (coverage != 0) page.drawPixel(destX + x, destY + y, 0xFFFFFF00.toInt() or coverage)
            }
        }
    }

    /** Downscales one master tile in linear light, carrying its colour through. */
    private fun emitColorTile(
        page: Pixmap,
        srcX: Int,
        srcY: Int,
        destX: Int,
        destY: Int,
        fit: FitRect,
    ) {
        val master = requireNotNull(colorMaster) { "TileSheetGlyphSource has been disposed" }
        val edge = TileSheetResample.edgeInk(master, masterWidth, srcX, srcY, masterTileWidthPx, masterTileHeightPx)
        val tile =
            TileSheetResample.downscaleColor(
                master,
                masterWidth,
                srcX,
                srcY,
                masterTileWidthPx,
                masterTileHeightPx,
                fit.width,
                fit.height,
                snapX = snapToPixelGrid && !edge.sides,
                snapY = snapToPixelGrid && !edge.topBottom,
            )
        for (y in 0 until fit.height) {
            for (x in 0 until fit.width) {
                val pixel = tile[y * fit.width + x]
                if (pixel and 0xFF != 0) page.drawPixel(destX + x, destY + y, pixel)
            }
        }
    }

    /** Where a downscaled tile lands inside its cell, per [scaling]. */
    private class FitRect(
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    /**
     * The output rect one tile occupies in a [w] x [h] cell: the whole cell under [TileScaling.STRETCH],
     * or the largest aspect-preserving rect centred in it under [TileScaling.PRESERVE_ASPECT].
     */
    private fun fitRect(
        w: Int,
        h: Int,
    ): FitRect =
        when (scaling) {
            TileScaling.STRETCH -> FitRect(w, h, 0, 0)
            TileScaling.PRESERVE_ASPECT -> {
                val scale = minOf(w.toDouble() / masterTileWidthPx, h.toDouble() / masterTileHeightPx)
                val fw = (masterTileWidthPx * scale).roundToInt().coerceIn(1, w)
                val fh = (masterTileHeightPx * scale).roundToInt().coerceIn(1, h)
                FitRect(fw, fh, (w - fw) / 2, (h - fh) / 2)
            }
        }

    /** GL_MAX_TEXTURE_SIZE for the current context (the largest texture the GPU will allocate). */
    private fun maxTextureSize(): Int {
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, glQueryBuffer)
        val value = glQueryBuffer.get(0)
        return if (value <= 0) 2048 else value // defensive: some drivers report 0 before first use
    }

    /** The sheet's pixels, read once at construction. */
    private class Loaded(
        val width: Int,
        val height: Int,
        val rgba: IntArray,
    )

    /**
     * Opens [sheet], checks it divides into whole tiles, and reads its pixels — releasing the Pixmap
     * before returning, so the master lives only as the plain array the resampler wants.
     */
    private fun load(sheet: FileHandle): Loaded {
        val pixmap = Pixmap(sheet)
        try {
            require(pixmap.width % columns == 0 && pixmap.height % rows == 0) {
                "sheet ${pixmap.width}x${pixmap.height} does not divide into ${columns}x$rows whole tiles"
            }
            return Loaded(pixmap.width, pixmap.height, readRgba(pixmap))
        } finally {
            pixmap.dispose()
        }
    }

    /**
     * Reads [pixmap] into packed RGBA8888 ints via its backing buffer rather than per-pixel `getPixel`,
     * which would be one native call per pixel across a master that can run to millions of them. A sheet
     * in any other format is converted first — the buffer layout is only known for RGBA8888.
     */
    private fun readRgba(pixmap: Pixmap): IntArray {
        val rgba8888 =
            if (pixmap.format == Pixmap.Format.RGBA8888) {
                null
            } else {
                Pixmap(pixmap.width, pixmap.height, Pixmap.Format.RGBA8888).apply {
                    blending = Pixmap.Blending.None
                    drawPixmap(pixmap, 0, 0)
                }
            }
        try {
            val source = rgba8888 ?: pixmap
            val buffer = source.pixels
            return IntArray(source.width * source.height) { i ->
                val at = i * 4
                ((buffer.get(at).toInt() and 0xFF) shl 24) or
                    ((buffer.get(at + 1).toInt() and 0xFF) shl 16) or
                    ((buffer.get(at + 2).toInt() and 0xFF) shl 8) or
                    (buffer.get(at + 3).toInt() and 0xFF)
            }
        } finally {
            rgba8888?.dispose()
        }
    }

    /** Disposes the atlas texture and releases the master pixels. */
    override fun dispose() {
        atlas?.dispose()
        atlas = null
        regions = emptyList()
        coverageMaster = null
        colorMaster = null
    }
}
