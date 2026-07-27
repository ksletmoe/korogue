package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.sletmoe.kotile.rendering.GammaDownsample
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How [FreeTypeGlyphSource] places each glyph within its cell (ADR-0036 tier-3
 * refinement, krogue-9x7.3) — the "literal text vs. tile" toggle.
 *
 * The two are different *placement* strategies, not just a size knob: text wants a
 * shared baseline and a uniform em so words read correctly; a map tile wants the
 * single glyph optimally centred and filling its cell.
 */
enum class GlyphFit {
    /**
     * Baseline-relative placement at a uniform em size, horizontally centred by the
     * glyph's advance — normal font layout. Letters share a baseline (so `a`, `g`,
     * `y` align and descenders hang), which is what running text needs. The default.
     */
    TEXT,

    /**
     * **Ink-centred, uniform-em** placement: each glyph's rendered ink box is centred
     * in its cell, and the whole page is enlarged by a single factor (so a capital
     * fills most of the cell) rather than fitting each glyph's box independently.
     * Glyphs keep their *relative* sizes — a period stays a small centred dot while
     * `@`/monsters/blocks fill — instead of tiny punctuation ballooning to fill a
     * cell. Best for single-glyph map cells (`@`, monsters, box-drawing, blocks);
     * wrong for running text, since centring every glyph individually destroys the
     * shared baseline. This is the pragmatic stand-in for Brogue's per-tile alignment
     * without an offline `optimizeTiles` search.
     */
    TILE,
}

/**
 * A resolution-independent [GlyphSource] that rasterises a **TrueType** face with
 * gdx-freetype at the exact cell pixel size — the tier-3, Brogue-fidelity route of
 * ADR-0036 (krogue-9x7.2).
 *
 * Where the bitmap [Font] is a fixed pixel grid that the window's
 * [com.sletmoe.kotile.rendering.ScalePolicy] scales (so a 12px glyph only ever
 * carries 12px of detail), this source re-rasterises the face whenever the cell
 * size changes ([prepareForCellSize]), so a glyph is drawn *at* the on-screen size
 * rather than scaled to it — smooth, high-resolution shapes crisp at any window
 * size.
 *
 * ## Brogue-style smoothing
 *
 * Rasterising straight at the cell px with FreeType's grid-fitting *hinting* gives
 * crisp but visibly aliased edges — the opposite of Brogue's look. So instead, per
 * [rasterize], the page is rendered at [supersample]× the cell size with **hinting
 * off** (the true outline, not stems snapped to pixels) and then downsampled to
 * cell resolution by repeated **gamma-correct 2:1 halving** in linear light (tier
 * 2's [com.sletmoe.kotile.rendering.GammaDownsample]). That is Brogue's method — a
 * high-resolution master resolved down in linear light — and yields the smooth,
 * non-pixel glyph aesthetic. The atlas holds white glyphs on transparent, tinted
 * per cell exactly like the bitmap sheet.
 *
 * ## Two ways to stay crisp at any size
 *
 * - **Resolution-independent (sharpest):** pair with
 *   [com.sletmoe.kotile.display.ascii.AsciiTileWindowConfig.resolutionIndependent],
 *   which re-rasterises this source at the on-screen cell px on every resize and
 *   renders 1:1. No scaling, no downsample at draw time.
 * - **Compose with tier 2:** rasterise a generous master size and drive a fixed
 *   grid with [com.sletmoe.kotile.rendering.FractionalScaleMode.SUPERSAMPLE], which
 *   gamma-downsamples the master to whatever the window's cell size is. Slightly
 *   softer, but no per-resize re-rasterisation.
 *
 * ## CP437 addressing
 *
 * This is a drop-in peer of [Font]: [glyph] is indexed by `char.code` as a **CP437
 * slot** (0–255), the same contract [Font] uses, so callers pass Latin-1 slot
 * bytes (`Char(219)` = full block) unchanged. Each slot is rasterised from the
 * Unicode character it depicts via [Cp437] (slot 219 → U+2588 █, slot 1 → U+263A
 * ☺, …). A slot whose glyph the face lacks renders as whatever the face's
 * `.notdef` is (typically a blank or a box); `char.code` outside 0–255 yields
 * `null`, as with [Font].
 *
 * ## Cost and lifetime
 *
 * Rasterisation builds a 16×16 atlas of the whole page and is **not** cheap; it
 * runs at construction and again on each *changed* [prepareForCellSize] (an
 * unchanged size is a no-op). It needs a live GL context (it renders the glyphs
 * through an offscreen [FrameBuffer]). Owns a GPU texture and the freetype
 * generator; [dispose] releases them.
 *
 * @param ttf the TrueType face to rasterise (see [Fonts.cascadiaMono] for the
 *   bundled default). Read once at construction; the caller may free the handle after.
 * @param cellWidthPx initial cell width in pixels
 * @param cellHeightPx initial cell height in pixels
 * @param supersample how many times the cell size to rasterise the master before the gamma-correct
 *   downsample; snapped to a power of two in `[1, 8]`. Higher is smoother (closer to Brogue) but builds
 *   a larger transient atlas. Defaults to `4`; `1` disables supersampling (rasterise straight at the
 *   cell px).
 * @param fit how each glyph is placed in its cell (ADR-0036 refinement, krogue-9x7.3). [GlyphFit.TEXT]
 *   (default) is normal baseline text layout; [GlyphFit.TILE] ink-centres and scale-fits each glyph for
 *   single-glyph map cells. See [GlyphFit].
 * @param glyphBrightness per-glyph brightness curve (krogue-9x7.3): after the downsample, each glyph is
 *   peak-normalised — a glyph whose densest pixel falls short of full ink is lifted toward it, so thin
 *   strokes and small glyphs don't read as washed-out grey next to bold ones (most visible at small cell
 *   sizes). This value is the **cap** on that per-glyph boost, clamped to `[1, 4]`; `1` (the default)
 *   disables the curve. A glyph already reaching full ink, or an empty cell, is left untouched.
 * @param snapToPixelGrid per-glyph output-pixel alignment (krogue-9x7.3) for crisper stems at small
 *   sizes. When on, the downsample runs a **per-glyph sub-pixel shift search**: for each glyph it tries a
 *   grid of sub-pixel offsets, box-downsamples the supersampled master at each, and keeps the offset that
 *   **minimises a blur metric** (`Σ sin(π·coverage)` — fewest half-lit, grey-edged pixels), so stems land
 *   on whole pixels rather than straddling them. This is a Kotlin re-implementation of the technique in
 *   **Brogue CE** (`tmewett/BrogueCE`, `src/platform/tiles.c`, `optimizeTiles`/`downscaleTile`, AGPL-3.0)
 *   — the algorithm, not its code. It is **not cheap** (a CPU search + downsample per glyph on every
 *   rasterise, i.e. on each [prepareForCellSize] resize), so it is off by default; best for fixed-size
 *   sources. For [GlyphFit.TEXT] it additionally **band-scales** the vertical resample (krogue-9x7.5): the
 *   x-height top and baseline are snapped to whole output rows so **lowercase** is crisp, not just
 *   caps/digits (see [bandScaleDownsample]); [GlyphFit.TILE] stays translation-only (no shared baseline to
 *   align). No offline shift cache either way.
 */
class FreeTypeGlyphSource internal constructor(
    ttf: FileHandle,
    cellWidthPx: Int,
    cellHeightPx: Int,
    supersample: Int = 4,
    private val fit: GlyphFit = GlyphFit.TEXT,
    glyphBrightness: Float = 1f,
    private val snapToPixelGrid: Boolean = false,
    // Module-internal test seam (krogue-9x7.5): force the translation-only shift search even for TEXT,
    // bypassing the x-height/baseline band scaling, so a same-module spec can A/B the two paths and prove
    // band scaling is what reduces lowercase blur. It is a *required* (named) argument so production can
    // only reach this constructor through the public one below (which passes `false`) — the test seam is
    // never part of the published surface, mirroring the `effectiveSupersample` internal seam.
    disableBandScale: Boolean,
) : GlyphSource {
    /**
     * Public production constructor (the published `com.sletmoe:kotile` surface). Identical to the
     * primary but without the module-internal `disableBandScale` test seam, which it fixes to `false` —
     * production always band-scales TEXT under [snapToPixelGrid]. See the class KDoc for the parameters.
     */
    constructor(
        ttf: FileHandle,
        cellWidthPx: Int,
        cellHeightPx: Int,
        supersample: Int = 4,
        fit: GlyphFit = GlyphFit.TEXT,
        glyphBrightness: Float = 1f,
        snapToPixelGrid: Boolean = false,
    ) : this(
        ttf,
        cellWidthPx,
        cellHeightPx,
        supersample,
        fit,
        glyphBrightness,
        snapToPixelGrid,
        disableBandScale = false,
    )

    private val generator = FreeTypeFontGenerator(ttf)

    // The per-glyph peak-normalisation cap (see the constructor doc). Clamped to a sane range; 1 = off.
    private val glyphBrightness: Float = glyphBrightness.coerceIn(1f, 4f)

    // Snapped to a power of two so the atlas downsamples by exact 2:1 gamma halving passes. Higher =
    // smoother edges (closer to Brogue's high-res-master look) at the cost of a larger transient atlas.
    private val supersample: Int = supersample.takeHighestOneBit().coerceIn(1, 8)

    // x-height/baseline band scaling (krogue-9x7.5, Brogue optimizeTiles part 2) applies only to TEXT
    // fit under snapToPixelGrid: it warps the vertical resample so both the x-height top and the baseline
    // land on whole output rows, which is what makes LOWERCASE crisp. It is baseline-relative, so it is
    // meaningless for TILE fit (each glyph is ink-centred independently, no shared baseline). When on, the
    // downsample runs bandScaleDownsample (warped vertical + horizontal shift search) instead of
    // shiftSearchDownsample (uniform box + 2-D translation search).
    private val bandScale: Boolean = snapToPixelGrid && fit == GlyphFit.TEXT && !disableBandScale

    override var charWidthPx: Int = cellWidthPx
        private set

    override var charHeightPx: Int = cellHeightPx
        private set

    // The current page atlas (upright, non-FBO texture) and its 256 cell regions. Replaced wholesale
    // on every (re)rasterise; the old texture is disposed there.
    private var atlas: Texture? = null
    private var regions: List<TextureRegion> = emptyList()

    // The gamma-correct 2:1 downsample shader (tier 2's), used to shrink the supersampled glyph atlas
    // to cell size in linear light. Compiled lazily; on failure the source falls back to rasterising
    // directly at the cell px (supersample = 1) rather than failing outright.
    private var downsampleShader: ShaderProgram? = null
    private var downsampleShaderFailed = false

    // Scratch for the GL_FRAMEBUFFER_BINDING / GL_VIEWPORT save-restore around the atlas render, so a
    // rasterise mid-app (a resize) cannot steal a framebuffer a caller had bound. Reused; see
    // GridCompositeCache for the same discipline.
    private val glQueryBuffer = BufferUtils.newIntBuffer(16)

    // One reusable batch + camera for all render passes, rather than allocating a SpriteBatch (which
    // compiles a shader and allocates buffers) per pass — rasterise runs on every resize step of the
    // resolution-independent path. Lazily created on first rasterise; released in dispose().
    private var renderBatch: SpriteBatch? = null
    private val renderCamera = OrthographicCamera()

    /**
     * The supersample factor the **last** [rasterize] actually used, after the shader-availability and
     * [maxTextureSize] caps. Exposed (module-internal) for tests: the orientation regression depends on
     * an *odd* number of 2:1 halving passes surviving, so a spec asserts this equals what it requested
     * rather than trusting the cap didn't quietly halve it to an even count on a low-`GL_MAX_TEXTURE_SIZE`
     * driver (where the GL specs are the only place a failure can be read).
     */
    internal var effectiveSupersample: Int = 0
        private set

    init {
        // Nothing the caller can reach holds a dispose() for this half-built object, so release what we
        // allocated (the generator, plus anything rasterize published) if the first rasterise throws.
        var ok = false
        try {
            rasterize(cellWidthPx, cellHeightPx)
            ok = true
        } finally {
            if (!ok) dispose()
        }
    }

    override fun glyph(character: Char): TextureRegion? = regions.getOrNull(character.code)

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
     * Rebuilds the 16×16 page atlas at a [w] x [h] cell size. To match Brogue's smooth look — a
     * high-resolution master downsampled in linear light, *not* stems hinted onto the pixel grid — this
     * rasterises the page at [supersample]× the cell size with **hinting off**, then shrinks it to cell
     * resolution by repeated **gamma-correct 2:1 halving** ([GammaDownsample]). If the downsample shader
     * is unavailable it falls back to rasterising directly at the cell px. The result is read into an
     * upright texture whose 256 cell regions become [regions].
     */
    private fun rasterize(
        w: Int,
        h: Int,
    ) {
        val atlasW = w * COLUMNS
        val atlasH = h * ROWS
        // Supersample only if the linear-light downsampler is available; otherwise 1:1 at the cell px.
        var ss = if (supersample > 1 && ensureDownsampleShader()) supersample else 1
        // Cap so the whole-page master atlas stays within GL_MAX_TEXTURE_SIZE — a master that exceeds it
        // fails to allocate and renders garbage. Halve until it fits (keeps the 2:1 downsample exact).
        val maxTex = maxTextureSize()
        while (ss > 1 && (atlasW * ss > maxTex || atlasH * ss > maxTex)) {
            ss /= 2
        }
        effectiveSupersample = ss

        val font =
            generator.generateFont(
                FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    size = h * ss
                    color = Color.WHITE
                    // gdx-freetype only rasterises the requested characters; its default set omits the
                    // box-drawing/block/Greek ranges CP437 needs, so ask for the whole page explicitly.
                    characters = Cp437.repertoire
                    // Supersampling wants the true outline, not grid-fitted stems — hinting off is what
                    // gives the smooth, high-res-master look. At ss=1 (no downsample) keep light hinting
                    // for legibility at small sizes.
                    hinting = if (ss > 1) FreeTypeFontGenerator.Hinting.None else FreeTypeFontGenerator.Hinting.Slight
                    minFilter = Texture.TextureFilter.Linear
                    magFilter = Texture.TextureFilter.Linear
                    genMipMaps = false
                },
            )

        // Each intermediate FBO / the font / the read-back pixmap is released even if a GL step throws
        // (an allocation or incomplete-FBO GdxRuntimeException) — the caller has no handle to these.
        val upright =
            try {
                preservingFrameBuffer {
                    // 1. Render every glyph centred in its ss-sized cell.
                    var buffer = FrameBuffer(Pixmap.Format.RGBA8888, atlasW * ss, atlasH * ss, false)
                    try {
                        renderGlyphs(buffer, font, w * ss, h * ss)
                        if (snapToPixelGrid && ss > 1) {
                            // 2a. Per-glyph shift-search downsample (Brogue's optimizeTiles technique): read
                            // the supersampled master back and align each glyph to the output-pixel grid on
                            // the CPU (see shiftSearchDownsample). Bypasses the GPU halving passes.
                            buffer.begin()
                            val rawMaster = Pixmap.createFromFrameBuffer(0, 0, atlasW * ss, atlasH * ss)
                            buffer.end()
                            val masterUp =
                                try {
                                    flipY(rawMaster)
                                } finally {
                                    rawMaster.dispose()
                                }
                            try {
                                if (bandScale) {
                                    bandScaleDownsample(masterUp, w, h, ss)
                                } else {
                                    shiftSearchDownsample(masterUp, w, h, ss)
                                }
                            } finally {
                                masterUp.dispose()
                            }
                        } else {
                            // 2b. Halve — gamma-correct, in linear light — until the atlas is at cell resolution.
                            var scale = ss
                            while (scale > 1) {
                                val half =
                                    FrameBuffer(Pixmap.Format.RGBA8888, buffer.width / 2, buffer.height / 2, false)
                                var halved = false
                                try {
                                    gammaHalve(half, buffer)
                                    halved = true
                                } finally {
                                    if (!halved) half.dispose() // gammaHalve threw; don't leak this FBO
                                }
                                buffer.dispose()
                                buffer = half
                                scale /= 2
                            }
                            // 3. Read the cell-resolution atlas back, upright (FBO pixels are bottom-up).
                            buffer.begin()
                            val raw = Pixmap.createFromFrameBuffer(0, 0, atlasW, atlasH)
                            buffer.end()
                            try {
                                flipY(raw)
                            } finally {
                                raw.dispose()
                            }
                        }
                    } finally {
                        buffer.dispose()
                    }
                }
            } finally {
                font.dispose()
            }

        // Per-glyph brightness curve (krogue-9x7.3): lift each cell's ink toward full opacity so thin
        // glyphs aren't dim. Runs on the small cell-resolution atlas, once per rasterise; 1f = no-op.
        if (glyphBrightness > 1f) applyPerGlyphBrightness(upright, w, h)

        val newAtlas =
            Texture(upright).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
        upright.dispose()

        atlas?.dispose()
        atlas = newAtlas
        regions =
            (0 until GLYPH_COUNT).map { slot ->
                TextureRegion(newAtlas, (slot % COLUMNS) * w, (slot / COLUMNS) * h, w, h)
            }
        charWidthPx = w
        charHeightPx = h
    }

    /**
     * Draws every CP437 glyph into [target] with **blending disabled**, so each glyph's straight-alpha
     * (white RGB, coverage in alpha) is written as-is rather than composited — keeping later passes and
     * the final tint free of premultiply/alpha-squaring artefacts at glyph edges.
     */
    private fun renderGlyphs(
        target: FrameBuffer,
        font: BitmapFont,
        cellW: Int,
        cellH: Int,
    ) {
        val batch = beginTargetPass(target, shader = null)
        drawAllGlyphs(font, batch, cellW, cellH, target.height)
        batch.end()
        target.end()
    }

    /**
     * Downsamples [source] into [target] (half the size) with the gamma-correct [GammaDownsample]
     * shader — a 2:1 box average in linear light. Blending is disabled so the shader's straight-alpha
     * output is written verbatim for the next pass.
     */
    private fun gammaHalve(
        target: FrameBuffer,
        source: FrameBuffer,
    ) {
        val shader = downsampleShader ?: return
        val texture = source.colorBufferTexture
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        val batch = beginTargetPass(target, shader)
        shader.setUniformf("u_footprintTexels", 2f, 2f)
        shader.setUniformf("u_srcTexel", 1f / source.width, 1f / source.height)
        // Drawing an FBO's colour texture into another FBO flips it vertically (FBO storage is
        // bottom-up); V-flip the source region so each halving pass PRESERVES orientation. Without this
        // the atlas is flipped once per pass — invisible only when the pass count happens to be even
        // (e.g. supersample 4 → 2 passes), and garbled for any odd count (supersample 8 → 3 passes).
        // The single upright correction is the flipY on read-back, independent of the pass count.
        batch.draw(
            TextureRegion(texture).apply { flip(false, true) },
            0f,
            0f,
            target.width.toFloat(),
            target.height.toFloat(),
        )
        batch.end()
        target.end()
    }

    /**
     * Binds [target], clears it transparent, and starts the shared [renderBatch] pointed at it with
     * [shader] (null = default) and blending disabled (straight-alpha writes; see [renderGlyphs]).
     * Returns the batch, already `begin`-ed; the caller draws, then `end()`s the batch and [target].
     */
    private fun beginTargetPass(
        target: FrameBuffer,
        shader: ShaderProgram?,
    ): SpriteBatch {
        target.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val batch = renderBatch ?: SpriteBatch().also { renderBatch = it }
        renderCamera.setToOrtho(false, target.width.toFloat(), target.height.toFloat())
        renderCamera.update()
        batch.projectionMatrix = renderCamera.combined
        batch.shader = shader
        batch.disableBlending()
        batch.begin()
        return batch
    }

    /** GL_MAX_TEXTURE_SIZE for the current context (the largest square texture/FBO the GPU will allocate). */
    private fun maxTextureSize(): Int {
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, glQueryBuffer)
        val v = glQueryBuffer.get(0)
        return if (v <= 0) 2048 else v // defensive: some drivers report 0 before first use
    }

    private fun ensureDownsampleShader(): Boolean {
        if (downsampleShaderFailed) return false
        downsampleShader?.let { return true }
        val program = ShaderProgram(GammaDownsample.VERTEX, GammaDownsample.FRAGMENT)
        if (!program.isCompiled) {
            Gdx.app?.error("FreeTypeGlyphSource", "gamma-downsample shader failed to compile: ${program.log}")
            program.dispose()
            downsampleShaderFailed = true
            return false
        }
        downsampleShader = program
        return true
    }

    /**
     * Draws every CP437 slot's glyph into its own [w] x [h] cell of the currently bound offscreen
     * buffer via [font]. The camera is a y-up ortho of the atlas; a cell at grid (col, row) — top-left
     * origin — has its top edge at world y `atlasH - row*h`. Placement follows [fit]: [GlyphFit.TEXT]
     * lays glyphs out on a shared baseline at a uniform em ([drawTextGlyph]); [GlyphFit.TILE] ink-centres
     * and scale-fits each glyph in its cell ([drawTileGlyph]).
     */
    private fun drawAllGlyphs(
        font: BitmapFont,
        batch: SpriteBatch,
        w: Int,
        h: Int,
        atlasH: Int,
    ) {
        // Scissor each glyph to its own cell: neither BitmapFont.draw nor the tile scale-fit clips, so a
        // glyph whose outline exceeds the cell (accents, tall box-drawing, or a scaled-up tile) would
        // otherwise overwrite the neighbouring CP437 slot in the shared atlas. The per-cell flush makes
        // each scissor take effect for its own draw (SpriteBatch buffers until flushed) — same discipline
        // as GridCompositeCache's partial recomposite.
        //
        // rasterise can run mid-app (a resolution-independent resize), so restore the caller's scissor
        // enable-state and box — preservingFrameBuffer only covers framebuffer + viewport.
        val hadScissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, glQueryBuffer)
        val savedScissor = IntArray(4) { glQueryBuffer.get(it) }
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        try {
            val layout = GlyphLayout()
            // One page-wide enlargement for TILE (uniform em): scale so a capital fills TILE_CAP_FILL of
            // the cell height. Per-glyph placement then keeps relative sizes and only clamps down anything
            // that would overflow its cell. capHeight is in the same (supersampled) px as w/h, so the ratio
            // is resolution-independent; guard a font that reports no capHeight.
            val tileEmScale =
                if (fit == GlyphFit.TILE && font.capHeight > 0f) TILE_CAP_FILL * h / font.capHeight else 1f
            for (slot in 0 until GLYPH_COUNT) {
                val codePoint = Cp437.toUnicode(slot)
                if (codePoint < 0) continue

                val col = slot % COLUMNS
                val row = slot / COLUMNS
                // Clip to this cell (GL scissor is bottom-left origin; the cell's bottom edge is
                // atlasH - (row+1)*h).
                Gdx.gl.glScissor(col * w, atlasH - (row + 1) * h, w, h)
                val drew =
                    when (fit) {
                        GlyphFit.TEXT -> drawTextGlyph(font, batch, layout, codePoint, col, row, w, h, atlasH)
                        GlyphFit.TILE -> drawTileGlyph(font, batch, codePoint, col, row, w, h, atlasH, tileEmScale)
                    }
                if (drew) batch.flush() // land this cell's geometry while its scissor is active
            }
        } finally {
            Gdx.gl.glScissor(savedScissor[0], savedScissor[1], savedScissor[2], savedScissor[3])
            if (!hadScissor) Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        }
    }

    /**
     * [GlyphFit.TEXT] placement: measure [codePoint] with [layout] and place it on a shared baseline so
     * all glyphs align and descenders hang — normal text layout. BitmapFont.draw takes (x, y) at the top
     * of the cap line and draws downward in the y-up world, so `baseline = drawY - capHeight`.
     *
     * **Descender room (krogue-ns5):** the cell is centred on the font's *line box* — cap height above
     * the baseline plus the descent below it — rather than on the cap box alone, so the baseline sits
     * `descent` (+ half the spare) above the cell floor and `g/j/p/q/y` tails fall inside the cell instead
     * of being clipped by the per-cell scissor. capHeight and descent are the same for every glyph, so the
     * baseline is identical across the whole page — the invariant the band-scale downsample relies on
     * ([bandScaleDownsample] measures the baseline once from the rendered `x`). Returns `false` (nothing
     * drawn) for a glyph with no ink, e.g. space.
     *
     * The glyph is rendered at its natural sub-pixel position; when [snapToPixelGrid] is on, the
     * per-glyph output-pixel alignment happens later in the downsample, not here.
     */
    private fun drawTextGlyph(
        font: BitmapFont,
        batch: SpriteBatch,
        layout: GlyphLayout,
        codePoint: Int,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        atlasH: Int,
    ): Boolean {
        layout.setText(font, String(Character.toChars(codePoint)))
        if (layout.width <= 0f && layout.height <= 0f) return false // nothing to draw (e.g. space)
        val drawX = (col * w) + (w - layout.width) / 2f
        // Centre the line box (capHeight + descent) in the cell; baseline = drawY - capHeight, so derive
        // drawY from the baseline sitting `descent + spare/2` above the cell floor. font.descent is
        // negative (below baseline), hence abs().
        val capHeight = layout.height
        val descentPx = abs(font.descent)
        val cellBottom = (atlasH - (row + 1) * h).toFloat()
        val spare = (h - (capHeight + descentPx)).coerceAtLeast(0f)
        val baseline = cellBottom + descentPx + spare / 2f
        font.draw(batch, layout, drawX, baseline + capHeight)
        return true
    }

    /**
     * [GlyphFit.TILE] placement: draw [codePoint]'s rendered **ink box** directly, ink-centred in the
     * cell at the page-wide [emScale] (uniform enlargement — see [GlyphFit.TILE]), clamped down per glyph
     * so nothing overflows its cell. The uniform scale keeps relative glyph sizes (a period stays a small
     * dot; a capital fills), while the clamp lets a full-em glyph (block, box-drawing) fill exactly rather
     * than overflow-and-clip. The glyph's page region ([BitmapFont.Glyph]) is drawn with **no extra
     * V-flip**: BitmapFont already stores its pages oriented for a y-up batch (which is why the
     * [GlyphFit.TEXT] `font.draw` path lands upright through the same downstream passes), so a raw region
     * draw matches it as-is; flipping here would render every glyph upside-down. Returns `false` when the
     * face has no inked glyph for the slot (e.g. space, or a missing glyph).
     */
    private fun drawTileGlyph(
        font: BitmapFont,
        batch: SpriteBatch,
        codePoint: Int,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        atlasH: Int,
        emScale: Float,
    ): Boolean {
        // codePoint is always in the BMP here (every Cp437 entry is < U+FFFF), so a single Char indexes it.
        val glyph = font.data.getGlyph(codePoint.toChar()) ?: return false
        if (glyph.width == 0 || glyph.height == 0) return false // no ink (space, or a zero-size glyph)

        // Uniform enlargement, but never larger than fits: a full-em glyph (block/box) fills exactly and a
        // wide glyph can't spill into a neighbour; punctuation stays at emScale (well under the fit cap).
        val scale = minOf(emScale, w.toFloat() / glyph.width, h.toFloat() / glyph.height)
        val drawW = glyph.width * scale
        val drawH = glyph.height * scale
        val region =
            TextureRegion(font.getRegion(glyph.page).texture, glyph.srcX, glyph.srcY, glyph.width, glyph.height)
        val drawX = (col * w) + (w - drawW) / 2f
        val drawY = (atlasH - (row + 1) * h) + (h - drawH) / 2f // y-up: cell's bottom edge + vertical centre
        batch.draw(region, drawX, drawY, drawW, drawH)
        return true
    }

    /**
     * Per-glyph brightness curve (krogue-9x7.3): for each [cellW] x [cellH] cell of [pix], find the
     * peak ink (max alpha) and scale every pixel's alpha by `min(glyphBrightness, 255/peak)` — lifting a
     * glyph whose densest pixel falls short of full opacity toward it, so thin/small glyphs read at a
     * weight consistent with bold ones. A cell that is empty or already reaches full ink is left as-is
     * (so a keyed-out/blank glyph — e.g. space — stays transparent per ADR-0029). RGB is untouched
     * (glyphs are straight-alpha white).
     *
     * Works on [pix]'s backing [Pixmap.getPixels] buffer directly (RGBA8888 → 4 bytes/pixel, alpha last)
     * rather than per-pixel `getPixel`/`drawPixel`: this pass runs on every `prepareForCellSize`
     * rerasterise when the curve is on, so the JNI round-trip per pixel would show up on resize.
     */
    private fun applyPerGlyphBrightness(
        pix: Pixmap,
        cellW: Int,
        cellH: Int,
    ) {
        val width = pix.width
        val buf = pix.pixels // direct ByteBuffer over the atlas; absolute get/put leave its position alone
        for (slot in 0 until GLYPH_COUNT) {
            val cx = (slot % COLUMNS) * cellW
            val cy = (slot / COLUMNS) * cellH

            var peak = 0
            for (y in cy until cy + cellH) {
                var idx = (y * width + cx) * 4 + 3 // alpha byte of the row's first cell pixel
                for (x in 0 until cellW) {
                    val a = buf.get(idx).toInt() and 0xFF
                    if (a > peak) peak = a
                    idx += 4
                }
            }
            if (peak <= MIN_INK_ALPHA || peak >= 255) continue // empty cell, or already at full ink

            val boost = minOf(glyphBrightness, 255f / peak)
            if (boost <= 1f) continue
            for (y in cy until cy + cellH) {
                var idx = (y * width + cx) * 4 + 3
                for (x in 0 until cellW) {
                    val a = buf.get(idx).toInt() and 0xFF
                    if (a != 0) buf.put(idx, minOf(255, (a * boost).roundToInt()).toByte())
                    idx += 4
                }
            }
        }
    }

    /**
     * Fills [sat] (size `(mW+1)·(mH+1)`, cleared first) with the per-cell summed-area table of master
     * alpha for the cell at master origin ([mx0], [my0]) in the [masterW]-wide [buf]: `sat[y·(mW+1)+x] =
     * Σ alpha over master `[0,x) × [0,y)``, so any axis-aligned box average over the cell is O(1). Shared
     * by [shiftSearchDownsample] and [bandScaleDownsample].
     */
    private fun buildCellSat(
        buf: java.nio.ByteBuffer,
        sat: IntArray,
        masterW: Int,
        mx0: Int,
        my0: Int,
        mW: Int,
        mH: Int,
    ) {
        val satW = mW + 1
        java.util.Arrays.fill(sat, 0)
        for (my in 0 until mH) {
            val srcBase = (my0 + my) * masterW + mx0
            val satRow = (my + 1) * satW
            val satPrev = my * satW
            var rowSum = 0
            for (mx in 0 until mW) {
                rowSum += buf.get((srcBase + mx) * 4 + 3).toInt() and 0xFF
                sat[satRow + mx + 1] = sat[satPrev + mx + 1] + rowSum
            }
        }
    }

    /**
     * For each of the [h] output rows, interpolates [sat] to the fractional y-band `[vy0, vy1]` and writes
     * a horizontal prefix into [rowBand] (`rowBand[oy·(mW+1)+x] = Σ master alpha over cols `[0,x)` × that
     * band`), so a box average over any x-span is one subtraction. The y-band edges are fractional master
     * rows (the band-scale warp); the SAT's column prefix is lerp'd between the bracketing integer rows.
     * The [bandScaleDownsample] inner-loop core, factored out to keep that function under detekt's limits.
     */
    private fun fillRowBand(
        sat: IntArray,
        rowBand: DoubleArray,
        vy0: DoubleArray,
        vy1: DoubleArray,
        mW: Int,
        mH: Int,
        h: Int,
    ) {
        val satW = mW + 1
        for (oy in 0 until h) {
            val y0i = vy0[oy].toInt().coerceIn(0, mH)
            val y1i = vy1[oy].toInt().coerceIn(0, mH)
            val f0 = vy0[oy] - y0i
            val f1 = vy1[oy] - y1i
            val lo0 = y0i * satW
            val lo1 = (y0i + 1).coerceAtMost(mH) * satW
            val hi0 = y1i * satW
            val hi1 = (y1i + 1).coerceAtMost(mH) * satW
            val rb = oy * satW
            for (x in 0..mW) {
                val low = sat[lo0 + x] + (sat[lo1 + x] - sat[lo0 + x]) * f0
                val high = sat[hi0 + x] + (sat[hi1 + x] - sat[hi0 + x]) * f1
                rowBand[rb + x] = high - low
            }
        }
    }

    /**
     * Per-glyph sub-pixel **shift-search** downsample — the crispness core of [snapToPixelGrid]. A Kotlin
     * re-implementation of the *technique* in **Brogue CE** (`tmewett/BrogueCE`, `src/platform/tiles.c`,
     * `optimizeTiles`/`downscaleTile`). Brogue is **AGPL-3.0**; kotile is **BSD-3-Clause**. This is
     * independent original code (a summed-area table, not Brogue's per-candidate accumulation) expressing a
     * non-copyrightable method — **no Brogue code is copied**, so it does not trigger AGPL. The credit is
     * provenance, not a licence grant. See docs/adr/0037 for the full reasoning.
     *
     * For each CP437 cell of the supersampled [masterUp] (white glyph, coverage in alpha) it tries a grid
     * of sub-pixel offsets, box-downsamples the master cell to [w]x[h] at each, and keeps the offset that
     * **minimises Brogue's blur metric** `Σ sin(π·coverage)` — the sum is smallest when the fewest pixels
     * are half-lit (grey-edged), i.e. when stems land squarely on output pixels. A per-cell summed-area
     * table makes each box average O(1), so the whole search is one CPU pass rather than [ss]² GL
     * readbacks. Coverage is straight-averaged, matching [GammaDownsample]'s alpha handling. Returns the
     * cell-resolution atlas (white RGB, aligned alpha), upright.
     */
    private fun shiftSearchDownsample(
        masterUp: Pixmap,
        w: Int,
        h: Int,
        ss: Int,
    ): Pixmap {
        val atlasW = w * COLUMNS
        val atlasH = h * ROWS
        val out =
            Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
            }
        val mW = w * ss // master cell width
        val mH = h * ss // master cell height
        val masterW = masterUp.width
        val buf = masterUp.pixels // RGBA8888 ByteBuffer; alpha is the 4th byte of each pixel
        val ssArea = (ss * ss).toFloat()
        val step = (ss / 4).coerceAtLeast(1) // sub-pixel search resolution: quarter of an output pixel
        val satW = mW + 1
        val sat = IntArray(satW * (mH + 1)) // reused per-cell summed-area table of master alpha

        for (slot in 0 until GLYPH_COUNT) {
            val col = slot % COLUMNS
            val row = slot / COLUMNS
            val mx0 = col * mW
            val my0 = row * mH

            buildCellSat(buf, sat, masterW, mx0, my0, mW, mH)

            // Search the offset grid; keep the one with the least blur (fewest half-lit output pixels).
            var bestSx = 0
            var bestSy = 0
            var bestBlur = Double.MAX_VALUE
            var sy = 0
            while (sy < ss) {
                var sx = 0
                while (sx < ss) {
                    var blur = 0.0
                    for (oy in 0 until h) {
                        val y0 = (oy * ss + sy).coerceIn(0, mH)
                        val y1 = (oy * ss + sy + ss).coerceIn(0, mH)
                        val ry0 = y0 * satW
                        val ry1 = y1 * satW
                        for (ox in 0 until w) {
                            val x0 = (ox * ss + sx).coerceIn(0, mW)
                            val x1 = (ox * ss + sx + ss).coerceIn(0, mW)
                            val sum = sat[ry1 + x1] - sat[ry0 + x1] - sat[ry1 + x0] + sat[ry0 + x0]
                            blur += sin(Math.PI * (sum / ssArea / 255f))
                        }
                    }
                    if (blur < bestBlur) {
                        bestBlur = blur
                        bestSx = sx
                        bestSy = sy
                    }
                    sx += step
                }
                sy += step
            }

            // Emit the cell downsampled at the winning offset (white RGB, straight-averaged alpha).
            val ox0 = col * w
            val oy0 = row * h
            for (oy in 0 until h) {
                val y0 = (oy * ss + bestSy).coerceIn(0, mH)
                val y1 = (oy * ss + bestSy + ss).coerceIn(0, mH)
                val ry0 = y0 * satW
                val ry1 = y1 * satW
                for (ox in 0 until w) {
                    val x0 = (ox * ss + bestSx).coerceIn(0, mW)
                    val x1 = (ox * ss + bestSx + ss).coerceIn(0, mW)
                    val sum = sat[ry1 + x1] - sat[ry0 + x1] - sat[ry1 + x0] + sat[ry0 + x0]
                    val a = (sum / ssArea).roundToInt().coerceIn(0, 255)
                    if (a != 0) out.drawPixel(ox0 + ox, oy0 + oy, 0xFFFFFF00.toInt() or a) // white RGB + aligned alpha
                }
            }
        }
        return out
    }

    /**
     * Per-glyph **x-height/baseline band-scaled** downsample — the lowercase-crispness core of
     * [snapToPixelGrid] for TEXT fit (krogue-9x7.5, "Brogue optimizeTiles part 2"). Where
     * [shiftSearchDownsample] aligns *one* horizontal reference by translation (caps/digits/box-drawing
     * gain, lowercase less so), this warps the **vertical** resample so **two** references — the x-height
     * top and the baseline — both land on whole output rows, so a lowercase letter's flat top and bottom
     * edges sit on the pixel grid rather than straddling two rows. This is the mechanism ADR-0037
     * deferred; a Kotlin re-implementation of the *technique* in **Brogue CE** (`tmewett/BrogueCE`,
     * `src/platform/tiles.c`, `downscaleTile`'s `map2 = round(map2)` / `map3 = round(map3)` text-tile
     * band snap), not its code — see [shiftSearchDownsample]'s note and docs/adr/0038.
     *
     * The shared baseline is inherent: [drawTextGlyph] lays every glyph out on one line at a constant
     * top (libGDX's `GlyphLayout.height` is the font cap height for any single glyph), so the x-height top
     * and baseline sit at the **same** master rows in every cell. We measure them once from the rendered
     * `x` cell — exactly how Brogue defines `TEXT_X_HEIGHT` ("height of the 'x' outline") and
     * `TEXT_BASELINE` — then build a piecewise-linear output→source vertical map that pins those two rows
     * to their rounded output rows and stretches the x-band between them to fit, leaving ascenders and
     * descenders at natural scale. Horizontally it keeps [shiftSearchDownsample]'s uniform-box **shift
     * search** (Brogue keeps the horizontal search active for text). Each output pixel is a box average
     * over an integer-width x-span and a **fractional-height** y-band (linearly interpolated through the
     * per-cell summed-area table), divided by the actual box area. If `x` has no measurable ink it falls
     * back to the uniform [shiftSearchDownsample]. Returns the cell-resolution atlas, upright.
     */
    private fun bandScaleDownsample(
        masterUp: Pixmap,
        w: Int,
        h: Int,
        ss: Int,
    ): Pixmap {
        val mW = w * ss
        val mH = h * ss
        val xBand = measureXBand(masterUp, mW, mH) ?: return shiftSearchDownsample(masterUp, w, h, ss)

        // Source (master) rows of the x-height top and baseline, shared by every cell. Baseline sits just
        // below the bottom inked row of `x`.
        val srcXTop = xBand.first.toDouble()
        val srcBase = (xBand.second + 1).toDouble()

        // Snap both references to whole output rows (Brogue's round(map2)/round(map3)); keep at least one
        // output row between them so the x-band never collapses.
        val outXTop = Math.round(srcXTop / ss).toInt()
        val outBase = Math.round(srcBase / ss).toInt().coerceAtLeast(outXTop + 1)

        // Piecewise-linear output-row → source-master-row map: natural slope (ss) above the x-top and
        // below the baseline, a stretched slope across the x-band so the two snapped references line up.
        val bandSlope = (srcBase - srcXTop) / (outBase - outXTop)
        val srcAt = { outY: Double ->
            when {
                outY <= outXTop -> srcXTop + (outY - outXTop) * ss
                outY <= outBase -> srcXTop + (outY - outXTop) * bandSlope
                else -> srcBase + (outY - outBase) * ss
            }
        }
        // Per-output-row source band [vy0, vy1], clamped into the master; identical for every cell.
        val vy0 = DoubleArray(h)
        val vy1 = DoubleArray(h)
        for (oy in 0 until h) {
            vy0[oy] = srcAt(oy.toDouble()).coerceIn(0.0, mH.toDouble())
            vy1[oy] = srcAt((oy + 1).toDouble()).coerceIn(0.0, mH.toDouble())
        }

        val atlasW = w * COLUMNS
        val atlasH = h * ROWS
        val out =
            Pixmap(atlasW, atlasH, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
            }
        val masterW = masterUp.width
        val buf = masterUp.pixels
        val step = (ss / 4).coerceAtLeast(1) // horizontal sub-pixel search resolution: quarter of a pixel
        val sxs = (0 until ss step step).toList()
        val satW = mW + 1
        val sat = IntArray(satW * (mH + 1)) // reused per-cell summed-area table of master alpha
        // rowBand[oy*(mW+1) + x] = Σ master alpha over cols [0,x) × the fractional y-band of output row oy.
        // A horizontal prefix, so a box average over [x0,x1) is one subtraction. Rebuilt per cell.
        val rowBand = DoubleArray(h * satW)
        val blur = DoubleArray(sxs.size)

        for (slot in 0 until GLYPH_COUNT) {
            val col = slot % COLUMNS
            val row = slot / COLUMNS
            val mx0 = col * mW
            val my0 = row * mH

            buildCellSat(buf, sat, masterW, mx0, my0, mW, mH)
            fillRowBand(sat, rowBand, vy0, vy1, mW, mH, h)

            // Horizontal shift search: pick the x-offset with the least blur (fewest half-lit pixels).
            java.util.Arrays.fill(blur, 0.0)
            for (oy in 0 until h) {
                val bandH = vy1[oy] - vy0[oy]
                if (bandH <= 0.0) continue
                val rb = oy * satW
                for (i in sxs.indices) {
                    val sx = sxs[i]
                    var b = 0.0
                    for (ox in 0 until w) {
                        val x0 = (ox * ss + sx).coerceIn(0, mW)
                        val x1 = (ox * ss + sx + ss).coerceIn(0, mW)
                        val area = (x1 - x0) * bandH
                        if (area <= 0.0) continue
                        val cov = (rowBand[rb + x1] - rowBand[rb + x0]) / area / 255.0
                        b += sin(Math.PI * cov)
                    }
                    blur[i] += b
                }
            }
            var bestI = 0
            for (i in sxs.indices) if (blur[i] < blur[bestI]) bestI = i
            val bestSx = sxs[bestI]

            // Emit the cell at the winning x-offset (white RGB, straight-averaged alpha).
            val ox0 = col * w
            val oy0 = row * h
            for (oy in 0 until h) {
                val bandH = vy1[oy] - vy0[oy]
                if (bandH <= 0.0) continue
                val rb = oy * satW
                for (ox in 0 until w) {
                    val x0 = (ox * ss + bestSx).coerceIn(0, mW)
                    val x1 = (ox * ss + bestSx + ss).coerceIn(0, mW)
                    val area = (x1 - x0) * bandH
                    if (area <= 0.0) continue
                    val a = ((rowBand[rb + x1] - rowBand[rb + x0]) / area).roundToInt().coerceIn(0, 255)
                    if (a != 0) out.drawPixel(ox0 + ox, oy0 + oy, 0xFFFFFF00.toInt() or a)
                }
            }
        }
        return out
    }

    /**
     * Measures the x-height band of the rendered master by scanning the `x` glyph's cell (CP437 slot
     * [X_SLOT]): returns the top and bottom inked master rows (cell-local) at ≥ half the cell's peak
     * coverage, or `null` if `x` has no ink. Mirrors Brogue's definition of the text band from the `x`
     * outline. The half-peak threshold locks onto the solid stroke rather than faint anti-aliased tails,
     * so the measured band matches the visible x-height. Since [drawTextGlyph] shares a baseline across
     * all glyphs, this one measurement fixes the band for the whole page.
     */
    private fun measureXBand(
        masterUp: Pixmap,
        mW: Int,
        mH: Int,
    ): Pair<Int, Int>? {
        val col = X_SLOT % COLUMNS
        val row = X_SLOT / COLUMNS
        val mx0 = col * mW
        val my0 = row * mH
        val masterW = masterUp.width
        val buf = masterUp.pixels
        var peak = 0
        for (my in 0 until mH) {
            val base = (my0 + my) * masterW + mx0
            for (mx in 0 until mW) {
                val a = buf.get((base + mx) * 4 + 3).toInt() and 0xFF
                if (a > peak) peak = a
            }
        }
        if (peak < MIN_INK_ALPHA) return null
        val threshold = peak / 2
        var top = -1
        var bottom = -1
        for (my in 0 until mH) {
            val base = (my0 + my) * masterW + mx0
            var rowMax = 0
            for (mx in 0 until mW) {
                val a = buf.get((base + mx) * 4 + 3).toInt() and 0xFF
                if (a > rowMax) rowMax = a
            }
            if (rowMax >= threshold) {
                if (top < 0) top = my
                bottom = my
            }
        }
        return if (top < 0) null else top to bottom
    }

    /** Returns a vertically flipped copy of [src], one row per native blit (not per pixel). */
    private fun flipY(src: Pixmap): Pixmap {
        val dst = Pixmap(src.width, src.height, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (y in 0 until src.height) {
            dst.drawPixmap(src, 0, y, 0, src.height - 1 - y, src.width, 1)
        }
        return dst
    }

    /** Runs [block] and restores whatever framebuffer + viewport were bound before it (the same discipline GridCompositeCache uses). */
    private inline fun <T> preservingFrameBuffer(block: () -> T): T {
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, glQueryBuffer)
        val handle = glQueryBuffer.get(0)
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, glQueryBuffer)
        val viewport = IntArray(4) { glQueryBuffer.get(it) }
        try {
            return block()
        } finally {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, handle)
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }
    }

    /** Disposes the atlas texture, the render batch, the downsample shader, and the freetype generator. */
    override fun dispose() {
        atlas?.dispose()
        atlas = null
        renderBatch?.dispose()
        renderBatch = null
        downsampleShader?.dispose()
        downsampleShader = null
        generator.dispose()
    }

    private companion object {
        const val GLYPH_COUNT = 256
        const val COLUMNS = 16
        const val ROWS = 16

        // Below this peak alpha a cell is treated as empty (no ink to lift) by the brightness curve, so
        // stray near-zero downsample noise can't trigger a large boost. ~3% of full opacity.
        const val MIN_INK_ALPHA = 8

        // CP437 slot of 'x' — the glyph the band-scale downsample measures the x-height/baseline from
        // (Brogue's TEXT_X_HEIGHT is likewise "the height of the 'x' outline"). ASCII 'x' == slot 120.
        const val X_SLOT = 120

        // TILE fit enlarges the page so a capital fills this fraction of the cell height (uniform em); a
        // small margin below 1 keeps ascenders/tall glyphs off the very edge. Full-em glyphs (block/box)
        // are clamped to fill exactly by the per-glyph fit cap in drawTileGlyph.
        const val TILE_CAP_FILL = 0.82f
    }
}
