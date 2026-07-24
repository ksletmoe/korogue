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
 * @param ttf the TrueType face to rasterise (see [Fonts.ubuntuMono] for the
 *   bundled default). Read once at construction; the caller may free the handle after.
 * @param cellWidthPx initial cell width in pixels
 * @param cellHeightPx initial cell height in pixels
 * @param supersample how many times the cell size to rasterise the master before the gamma-correct
 *   downsample; snapped to a power of two in `[1, 8]`. Higher is smoother (closer to Brogue) but builds
 *   a larger transient atlas. Defaults to `4`; `1` disables supersampling (rasterise straight at the
 *   cell px).
 */
class FreeTypeGlyphSource(
    ttf: FileHandle,
    cellWidthPx: Int,
    cellHeightPx: Int,
    supersample: Int = 4,
) : GlyphSource {
    private val generator = FreeTypeFontGenerator(ttf)

    // Snapped to a power of two so the atlas downsamples by exact 2:1 gamma halving passes. Higher =
    // smoother edges (closer to Brogue's high-res-master look) at the cost of a larger transient atlas.
    private val supersample: Int = supersample.takeHighestOneBit().coerceIn(1, 8)

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

    init {
        rasterize(cellWidthPx, cellHeightPx)
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
                        // 2. Halve — gamma-correct, in linear light — until the atlas is at cell resolution.
                        var scale = ss
                        while (scale > 1) {
                            val half = FrameBuffer(Pixmap.Format.RGBA8888, buffer.width / 2, buffer.height / 2, false)
                            try {
                                gammaHalve(half, buffer)
                            } catch (t: Throwable) {
                                half.dispose()
                                throw t
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
                    } finally {
                        buffer.dispose()
                    }
                }
            } finally {
                font.dispose()
            }

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
     * Draws every CP437 slot's glyph, each centred in its own [w] x [h] cell, into the currently bound
     * offscreen buffer via [font]. The camera is a y-up ortho of the atlas; a cell at grid (col, row)
     * — top-left origin — has its top edge at world y `atlasH - row*h`. Horizontal centring uses the
     * measured glyph width; vertical centring uses the measured line height, both from a [GlyphLayout].
     */
    private fun drawAllGlyphs(
        font: BitmapFont,
        batch: SpriteBatch,
        w: Int,
        h: Int,
        atlasH: Int,
    ) {
        // Scissor each glyph to its own cell: BitmapFont.draw doesn't clip, so a glyph whose outline
        // exceeds the cell (accents, tall box-drawing, or an overflowing em) would otherwise overwrite
        // the neighbouring CP437 slot in the shared atlas. The per-cell flush makes each scissor take
        // effect for its own draw (SpriteBatch buffers until flushed) — same discipline as
        // GridCompositeCache's partial recomposite.
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
        try {
            val layout = GlyphLayout()
            for (slot in 0 until GLYPH_COUNT) {
                val codePoint = Cp437.toUnicode(slot)
                if (codePoint < 0) continue
                val text = String(Character.toChars(codePoint))
                layout.setText(font, text)
                if (layout.width <= 0f && layout.height <= 0f) continue // nothing to draw (e.g. space)

                val col = slot % COLUMNS
                val row = slot / COLUMNS
                val cellLeft = (col * w).toFloat()
                val cellTopWorldY = (atlasH - row * h).toFloat()

                // Clip to this cell (GL scissor is bottom-left origin; the cell's bottom edge is atlasH
                // - (row+1)*h). BitmapFont.draw places (x, y) at the top of the line and draws downward
                // in the y-up world; centre the measured glyph box within the cell.
                Gdx.gl.glScissor(col * w, atlasH - (row + 1) * h, w, h)
                val drawX = cellLeft + (w - layout.width) / 2f
                val drawY = cellTopWorldY - (h - layout.height) / 2f
                font.draw(batch, layout, drawX, drawY)
                batch.flush() // land this cell's geometry while its scissor is active
            }
        } finally {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        }
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
    }
}
