package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A source of glyphs for [AsciiTileWindow]: it reports the native cell size the
 * window sizes its [com.sletmoe.kotile.display.KotileCanvas] from, and maps a
 * character to the texture region drawn for that cell (tinted by the cell's
 * foreground colour).
 *
 * This is the seam (ADR-0036, krogue-9x7) that lets [AsciiTileWindow] hold a
 * glyph provider without knowing which kind it is. Three implementations ship:
 *
 * - [Font] — a fixed bitmap CP437 sheet, size-agnostic: its glyphs are a fixed
 *   pixel grid, scaled by the window's
 *   [com.sletmoe.kotile.rendering.ScalePolicy]. The default.
 * - [FreeTypeGlyphSource] — a TrueType face rasterised *at* the cell size
 *   (ADR-0036 tier 3, krogue-9x7.2), smooth at any size.
 * - [TileSheetGlyphSource] — a high-resolution artist tilesheet downscaled to the
 *   cell size (ADR-0043, krogue-9x7.7), for drawn tiles a font cannot give.
 *
 * The two resolution-independent sources rebuild themselves through the
 * size-parametric [prepareForCellSize] hook, which [Font] ignores.
 *
 * Owns GPU resources; [dispose] releases them (see [AsciiTileWindow]'s ownership
 * contract for who calls it).
 */
interface GlyphSource : Disposable {
    /** Native glyph cell width in px; the canvas's native tile width. */
    val charWidthPx: Int

    /** Native glyph cell height in px; the canvas's native tile height. */
    val charHeightPx: Int

    /**
     * The texture region to draw for [character], or `null` if [character] is
     * outside this source's repertoire (for the bundled [Font], a code point
     * beyond the 256-glyph CP437 page).
     */
    fun glyph(character: Char): TextureRegion?

    /**
     * Size-parametric hook (ADR-0036 tier 3, krogue-9x7.2): (re)rasterise this
     * source's glyphs for a target cell of [widthPx] x [heightPx] pixels.
     *
     * The default is a **no-op**: the bundled bitmap [Font] is size-agnostic — its
     * glyphs are a fixed pixel grid scaled by the window's
     * [com.sletmoe.kotile.rendering.ScalePolicy] — so it ignores this. A
     * resolution-independent source regenerates its glyphs at the requested size and
     * updates [charWidthPx]/[charHeightPx] to match, so they are produced at — rather
     * than scaled to — the on-screen cell size: [FreeTypeGlyphSource] re-rasterises the
     * TrueType face, and [TileSheetGlyphSource] re-downscales its hi-res sheet.
     *
     * Calling with the current size is a no-op (implementations short-circuit an
     * unchanged size), so a caller may invoke it every resize cheaply.
     */
    fun prepareForCellSize(
        widthPx: Int,
        heightPx: Int,
    ) {
    }
}

/**
 * A bitmap font loaded from a classpath image laid out as a 16x16 CP437 code
 * page (256 glyphs in row-major order, code point 0 in the top-left cell).
 * Owns a GPU texture and must be [dispose]d when no longer needed.
 *
 * ## Using a bundled font
 *
 * The library ships with crisp 1-bit CP437 sheets from 8×8 to 16×16 (see
 * [Fonts]). Use [Fonts.cp437_10x10] to get a ready-to-use instance:
 *
 * ```kotlin
 * val font = Fonts.cp437_10x10()
 * ```
 *
 * ## Supplying a custom font
 *
 * Any CP437-layout sheet can be loaded by placing it on the classpath and
 * constructing a [Font] directly. For a Gradle/Maven project, put the file
 * under `src/main/resources` (or your test resources) and pass its path
 * relative to the classpath root:
 *
 * ```kotlin
 * // src/main/resources/fonts/cp437_12x12.png, 12x12 pixels per glyph,
 * // black background keyed to transparent.
 * val font = Font("fonts/cp437_12x12.png", charWidthPx = 12, charHeightPx = 12)
 *
 * val window = AsciiTileWindow.create {
 *     this.font = font
 *     widthInTiles = 80
 *     heightInTiles = 25
 * }
 * ```
 *
 * If your sheet already has an alpha channel (no key color needed), pass
 * `keyColor = null`:
 *
 * ```kotlin
 * val font = Font("fonts/my_font_alpha.png", charWidthPx = 8, charHeightPx = 16, keyColor = null)
 * ```
 *
 * @param fontFileName classpath-relative path to the font sheet image (e.g.
 *   `"fonts/cp437_12x12.png"`). Resolved via `Gdx.files.classpath(...)` at
 *   construction time.
 * @param charWidthPx width of a single glyph cell in the sheet, in pixels
 * @param charHeightPx height of a single glyph cell in the sheet, in pixels
 * @param keyColor a solid color in the sheet that will be made fully transparent
 *   so glyphs composite cleanly over a cell's background color. Defaults to
 *   [Color.BLACK]. Pass `null` if the sheet already carries an alpha channel.
 * @param useMipMaps generate mipmaps and use a trilinear min-filter so glyphs
 *   stay clean when the font is drawn below its native size (see [TileSheet]).
 *   Defaults to `true`; magnification is nearest-neighbour regardless, so
 *   upscaling stays crisp.
 */
class Font(
    fontFileName: String,
    override val charWidthPx: Int,
    override val charHeightPx: Int,
    keyColor: Color? = Color.BLACK,
    useMipMaps: Boolean = true,
) : GlyphSource {
    private val tileSheet =
        TileSheet(Gdx.files.classpath(fontFileName), charWidthPx, charHeightPx, keyColor, useMipMaps = useMipMaps)

    private val glyphs: List<TextureRegion> =
        (0 until GLYPH_COUNT).map { index ->
            tileSheet.region(index % COLUMNS, index / COLUMNS)
        }

    /**
     * Returns the glyph region for [character], or `null` if its code point is
     * outside the 256-glyph code page.
     */
    override fun glyph(character: Char): TextureRegion? = glyphs.getOrNull(character.code)

    /** Disposes the underlying glyph sheet. */
    override fun dispose() = tileSheet.dispose()

    private companion object {
        const val GLYPH_COUNT = 256
        const val COLUMNS = 16
    }
}

/**
 * Built-in [Font]s bundled with the library.
 *
 * This object provides factory functions for the font sheets that ship inside
 * the kotile JAR. Each function allocates a GPU texture and returns a [Font]
 * that the caller is responsible for [Font.dispose]ing.
 *
 * ## Bundled fonts
 *
 * AsciiPanel's CP437 "system" fonts (Trystan Spangler, MIT — see
 * `cp437-fonts.license.txt`), each a crisp 1-bit sheet (black keyed to
 * transparent). Square sizes plus the classic 9×16 DOS-text aspect:
 *
 * - [cp437_8x8] — 8×8
 * - [cp437_9x16] — 9×16 (tall; classic DOS text aspect)
 * - [cp437_10x10] — 10×10
 * - [cp437_12x12] — 12×12
 * - [cp437_16x16] — 16×16
 *
 * ## Bundled vector faces (freetype, resolution-independent)
 *
 * TrueType faces rasterised *at* the cell pixel size via [FreeTypeGlyphSource] (ADR-0036 tier 3),
 * smooth at any size. Each has full or broad CP437 coverage and a permissive licence:
 *
 * - [cascadiaMono] — **recommended default**: Cascadia Mono Bold, complete CP437 coverage, crispest
 *   downscale (OFL 1.1). Selected in krogue-9x7.8 / ADR-0039.
 * - [dejaVuSansMono] — broad-coverage alternative: DejaVu Sans Mono Bold, complete CP437 coverage,
 *   wider/heavier letterforms (DejaVu licence).
 *
 * ## Custom fonts
 *
 * To use a font not listed here, construct a [Font] directly with the
 * classpath-relative path to your sheet and its glyph dimensions. See the
 * [Font] class documentation for a full example.
 */
object Fonts {
    // cp437_10x10 deliberately mirrors the bundled asset filename (cp437_10x10.png)
    // and is established public API, so it keeps its underscore-and-digits name and
    // suppresses ktlint's camelCase function-naming rule rather than break consumers.

    /**
     * Returns the bundled 10×10 pixel CP437 bitmap font.
     *
     * [keyColor] (default [Color.BLACK]) is made fully transparent so glyphs
     * composite over a cell's background color. Pass `null` if you want to
     * preserve the original sheet pixels without keying.
     *
     * The caller owns the returned [Font] and must [Font.dispose] it when done.
     *
     * Provenance: AsciiPanel's CP437 fonts (Trystan Spangler), MIT-licensed;
     * see `cp437-fonts.license.txt` on the classpath.
     *
     * @param keyColor the solid color in the sheet to treat as transparent;
     *   defaults to [Color.BLACK]
     */
    @Suppress("ktlint:standard:function-naming")
    fun cp437_10x10(keyColor: Color? = Color.BLACK): Font = Font("cp437_10x10.png", 10, 10, keyColor)

    /**
     * Returns the bundled 8×8 pixel CP437 bitmap font (the smallest bundled size).
     * See [cp437_10x10] for the [keyColor] contract and provenance. The caller owns
     * the returned [Font] and must [Font.dispose] it.
     */
    @Suppress("ktlint:standard:function-naming")
    fun cp437_8x8(keyColor: Color? = Color.BLACK): Font = Font("cp437_8x8.png", 8, 8, keyColor)

    /**
     * Returns the bundled 9×16 pixel CP437 bitmap font — the classic (tall)
     * DOS-text aspect rather than a square cell. See [cp437_10x10] for the
     * [keyColor] contract and provenance. The caller owns the returned [Font] and
     * must [Font.dispose] it.
     */
    @Suppress("ktlint:standard:function-naming")
    fun cp437_9x16(keyColor: Color? = Color.BLACK): Font = Font("cp437_9x16.png", 9, 16, keyColor)

    /**
     * Returns the bundled 12×12 pixel CP437 bitmap font. See [cp437_10x10] for the
     * [keyColor] contract and provenance. The caller owns the returned [Font] and
     * must [Font.dispose] it.
     */
    @Suppress("ktlint:standard:function-naming")
    fun cp437_12x12(keyColor: Color? = Color.BLACK): Font = Font("cp437_12x12.png", 12, 12, keyColor)

    /**
     * Returns the bundled 16×16 pixel CP437 bitmap font (the largest bundled size;
     * native for a 16px tile grid, e.g. alongside 16×16 sprite tiles). See
     * [cp437_10x10] for the [keyColor] contract and provenance. The caller owns the
     * returned [Font] and must [Font.dispose] it.
     */
    @Suppress("ktlint:standard:function-naming")
    fun cp437_16x16(keyColor: Color? = Color.BLACK): Font = Font("cp437_16x16.png", 16, 16, keyColor)

    // ── Vector (freetype) faces ────────────────────────────────────────────────────────────────────
    // The tier-3, resolution-independent glyph sources (ADR-0036, krogue-9x7.2): a TrueType face
    // rasterised *at* the cell pixel size rather than scaled from a fixed bitmap. Two faces are
    // bundled; they share the [freeType] parameter contract below. Cascadia Mono is the recommended
    // default (krogue-9x7.8 / ADR-0039) — complete CP437 coverage and the crispest downscale of the
    // candidates; DejaVu Sans Mono is a broad-coverage alternative.

    /**
     * Returns a [FreeTypeGlyphSource] on the bundled **Cascadia Mono** (Bold) TrueType face — the
     * **recommended default** vector glyph source (ADR-0036 tier 3, krogue-9x7.2; selected in
     * krogue-9x7.8 / ADR-0039). Glyphs are rasterised *at* the [cellWidthPx] x [cellHeightPx] cell size
     * (smooth at any size, re-rasterised on [GlyphSource.prepareForCellSize]) rather than scaled from a
     * fixed bitmap.
     *
     * Cascadia Mono is chosen for two reasons: **complete CP437 coverage** — all 253 distinct code
     * points a CP437 grid needs (box-drawing, block/shade elements, the control-range symbols ☺☻♥♦♣♠♪♫,
     * arrows, Greek, math), none falling back to `.notdef` — and its **Bold**, screen-tuned letterforms
     * survive the gamma-correct downscale crisper than a regular-weight face at small cell sizes (the
     * Brogue-fidelity goal). See [dejaVuSansMono] for a broad-coverage alternative.
     *
     * Unlike the `cp437_*` bitmap fonts this is **not** free — it renders the whole 256-glyph page
     * through an offscreen buffer, so build it once and reuse it. The caller owns the returned source and
     * must [GlyphSource.dispose] it.
     *
     * Provenance: Cascadia Mono, SIL Open Font Licence 1.1; see `cascadia-mono.license.txt` and
     * `fonts/CascadiaMono-LICENSE.txt` on the classpath.
     *
     * See [freeType] for the [fit], [glyphBrightness], and [snapToPixelGrid] refinement parameters.
     */
    fun cascadiaMono(
        cellWidthPx: Int = 16,
        cellHeightPx: Int = 16,
        fit: GlyphFit = GlyphFit.TEXT,
        glyphBrightness: Float = 1f,
        snapToPixelGrid: Boolean = false,
    ): FreeTypeGlyphSource =
        freeType("fonts/CascadiaMono-Bold.ttf", cellWidthPx, cellHeightPx, fit, glyphBrightness, snapToPixelGrid)

    /**
     * Returns a [FreeTypeGlyphSource] on the bundled **DejaVu Sans Mono** (Bold) TrueType face — a
     * broad-coverage **alternative** to the default [cascadiaMono] (krogue-9x7.8 / ADR-0039). Like
     * Cascadia Mono it has **complete CP437 coverage** (all 253 distinct code points, none `.notdef`)
     * and a Bold weight that stays crisp under the downscale, but with a wider, heavier, more neutral
     * letterform. Rasterised *at* the cell pixel size, same as [cascadiaMono].
     *
     * Not free (renders the whole page through an offscreen buffer); build once and reuse. The caller
     * owns the returned source and must [GlyphSource.dispose] it.
     *
     * Provenance: DejaVu Sans Mono, the DejaVu (Bitstream Vera + Arev) licence; see
     * `dejavu-sans-mono.license.txt` and `fonts/DejaVuSansMono-LICENSE.txt` on the classpath.
     *
     * See [freeType] for the [fit], [glyphBrightness], and [snapToPixelGrid] refinement parameters.
     */
    fun dejaVuSansMono(
        cellWidthPx: Int = 16,
        cellHeightPx: Int = 16,
        fit: GlyphFit = GlyphFit.TEXT,
        glyphBrightness: Float = 1f,
        snapToPixelGrid: Boolean = false,
    ): FreeTypeGlyphSource =
        freeType("fonts/DejaVuSansMono-Bold.ttf", cellWidthPx, cellHeightPx, fit, glyphBrightness, snapToPixelGrid)

    /**
     * Shared builder for the bundled vector faces: a [FreeTypeGlyphSource] on the classpath TTF at
     * [classpathTtf], rasterised at [cellWidthPx] x [cellHeightPx].
     *
     * The [fit] and [glyphBrightness] refinements (krogue-9x7.3) are off by default (normal text layout,
     * no brightness curve). Pass [GlyphFit.TILE] for a single-glyph map source (ink-centred, scale-fit
     * per glyph) and/or a `glyphBrightness > 1` to lift thin glyphs at small sizes; see
     * [FreeTypeGlyphSource].
     *
     * @param classpathTtf classpath-relative path to the bundled TrueType face
     * @param cellWidthPx initial cell width in pixels (default 16)
     * @param cellHeightPx initial cell height in pixels (default 16)
     * @param fit per-glyph placement strategy (default [GlyphFit.TEXT]); see [GlyphFit]
     * @param glyphBrightness per-glyph brightness-curve cap (default `1f` = off); see [FreeTypeGlyphSource]
     * @param snapToPixelGrid snap glyph placement to whole on-screen pixels for crisper stems (default
     *   `false`); see [FreeTypeGlyphSource]
     */
    private fun freeType(
        classpathTtf: String,
        cellWidthPx: Int,
        cellHeightPx: Int,
        fit: GlyphFit,
        glyphBrightness: Float,
        snapToPixelGrid: Boolean,
    ): FreeTypeGlyphSource =
        FreeTypeGlyphSource(
            com.badlogic.gdx.Gdx.files.classpath(classpathTtf),
            cellWidthPx,
            cellHeightPx,
            fit = fit,
            glyphBrightness = glyphBrightness,
            snapToPixelGrid = snapToPixelGrid,
        )
}
