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
 * ## Box drawing and blocks tile seamlessly
 *
 * The box-drawing and block/shade slots (`0xB0–0xDF`: `─ │ ┼ ╔ █ ▄ ░ ▒ ▓`) are
 * *cell-filling* — a frame or a wall only looks right if each glyph's strokes run
 * edge to edge and meet the neighbouring cell's. Centring them by ink cannot do
 * that once the target cell's aspect differs from the face's own (a square cell
 * with a tall mono face leaves a gap on one axis), so this source places that
 * whole class differently: it maps the face's design cell — measured from its full
 * block — onto the cell rect and draws them through it, edge-snapped
 * ([drawCellFillingGlyph], krogue-9x7.4). Every other glyph keeps its [fit]
 * placement. An aspect mismatch then shows up as horizontal and vertical strokes
 * differing in weight rather than as a seam. Under [snapToPixelGrid] the class gets
 * its own alignment too — a warp that holds the cell edges still and snaps the
 * *stroke* edges between them ([emitCellFillingCell], krogue-tg5).
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
 *   align). The winning offsets are **cached per cell size** (krogue-9x7.6, see [shiftCache]), so a size
 *   this source has already rasterised pays only the downsample, not the search — a resize that walks back
 *   over sizes it has seen gets most of the cost back. There is still no *offline* cache (Brogue
 *   precomputes every size at startup); the cache is per-instance and fills as sizes are visited.
 */
class FreeTypeGlyphSource internal constructor(
    ttf: FileHandle,
    cellWidthPx: Int,
    cellHeightPx: Int,
    supersample: Int = 4,
    private val fit: GlyphFit = GlyphFit.TEXT,
    glyphBrightness: Float = 1f,
    private val snapToPixelGrid: Boolean = false,
    textFill: Float = 1f,
    /**
     * Extra **Unicode code points** to place on the box-drawing grid rather than by the [fit]'s
     * baseline-relative rules — see [isCellFilling].
     *
     * Box-drawing and block glyphs are positioned by mapping one design cell onto the cell
     * ([drawCellFillingGlyph]) so they tile; every other glyph is placed from text metrics. Those two
     * rules are independent and generally disagree, which is invisible until a glyph from the second
     * group has to *meet* one from the first. The case that motivated this is a roguelike drawing a door
     * as `+` inside a run of connected wall: measured on Cascadia Mono Bold at a 48px cell, `+` centres
     * on row 23.18 and `─` on 26.44, so the door floats 3.26px — nearly 7% of the cell — above the wall
     * it is set into.
     *
     * Naming a code point here routes it through the cell-filling placement, onto the same grid. It does
     * not make the glyph fill its cell or change its shape: the placement is one shared linear transform,
     * so a glyph the *face* already aligns with the box strokes lands exactly on them (`+` and `─` share
     * a vertical centre of 0.346em in Cascadia), and the [snapToPixelGrid] emit for this class pins the
     * cell edges while snapping only interior stroke edges.
     *
     * The trade is that the same glyph is then placed off the text baseline everywhere *else* it appears,
     * so name only glyphs whose grid alignment matters more than their alignment in prose.
     */
    private val boxAlignedGlyphs: Set<Int> = emptySet(),
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
        textFill: Float = 1f,
        boxAlignedGlyphs: Set<Int> = emptySet(),
    ) : this(
        ttf,
        cellWidthPx,
        cellHeightPx,
        supersample,
        fit,
        glyphBrightness,
        snapToPixelGrid,
        textFill,
        boxAlignedGlyphs,
        disableBandScale = false,
    )

    private val generator = FreeTypeFontGenerator(ttf)

    // The per-glyph peak-normalisation cap (see the constructor doc). Clamped to a sane range; 1 = off.
    private val glyphBrightness: Float = glyphBrightness.coerceIn(1f, 4f)

    /**
     * How much of the cell [GlyphFit.TEXT] glyphs are grown to fill, as a multiple of the size at which
     * the face's whole ink box fits. `1` (the default) is that conservative fit: cap height, the
     * accent headroom above it, and the descender all sit inside the cell, so nothing can ever clip.
     *
     * The catch is that the headroom is charged to *every* cell whether or not anything uses it. For a
     * typical face the reserved box is ~1.16em against a 0.69em capital, so a capital ends up barely
     * over half the cell — while a cell-filling bitmap page draws its capitals at ~0.875 of the cell.
     * A game that offers both looks sees the vector one as dramatically smaller at the same cell size.
     *
     * Raising this spends that headroom on glyph size. What it costs is bounded and predictable: the
     * accent room above the capitals goes first (so diacritics clip before anything else), and only
     * past roughly `(1 - 2 * marginFrac) / ((capHeight + descent) / em)` — about `1.33` for a typical
     * mono face — do plain capitals and descenders stop fitting. A game drawing ASCII and box-drawing
     * can take most of it; one rendering accented text should leave this at `1`.
     *
     * Clamped to `[0.5, 2]`.
     */
    private val textFill: Float = textFill.coerceIn(0.5f, 2f)

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

    // Cached full-ink-box / em ratio for this face (krogue-ux6), measured once from a reference
    // rasterisation (see [faceInkBoxRatio]) and reused every rasterise — it is a face constant, so the
    // TEXT em-shrink never pays for a second full-page generation on resize.
    private var cachedInkBoxRatio: Float? = null

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

    /**
     * How many glyphs the **last** [rasterize] actually ran the [snapToPixelGrid] shift search for: the
     * full searchable page on a cell size this source has not rasterised before, and **0** on one already
     * in [shiftCache] (or when the snap path did not run at all). Exposed (module-internal) for tests,
     * because it is the only place the cache is observable — it is a pure memoisation, so the atlas it
     * produces is identical either way and no pixel assertion could tell a hit from a miss.
     */
    internal var lastShiftSearchCount: Int = 0
        private set

    /**
     * Wall-clock nanos the **last** [rasterize] spent in each of its stages (krogue-4jh). Exposed
     * (module-internal) for the profiling harness: krogue-9x7.6 measured that the shift search is a
     * minority of a resize's cost, and the only way to find where the rest goes is from inside
     * [rasterize] — every stage below is private. Stages that did not run for a given configuration stay
     * 0 (e.g. [halveNanos] on the snap path, [downsampleNanos] on the GPU path), so they sum to at most
     * [totalNanos]; the remainder is the un-attributed glue (FBO allocation, region rebuild).
     *
     * **These are only attributable when [profileSyncGl] is set.** GL commands queue asynchronously, so
     * without a sync point at each boundary the GPU work bills to whichever later stage happens to block
     * on it — which for this pipeline is the `glReadPixels` inside [readbackNanos], making the render
     * look free and the read-back look enormous.
     */
    internal class RasterizeTiming {
        var fontGenNanos = 0L
        var renderNanos = 0L
        var halveNanos = 0L
        var readbackNanos = 0L
        var flipNanos = 0L
        var downsampleNanos = 0L
        var brightnessNanos = 0L
        var padNanos = 0L
        var uploadNanos = 0L
        var totalNanos = 0L
    }

    /** Per-stage timings of the last [rasterize]; see [RasterizeTiming]. */
    internal val lastTiming = RasterizeTiming()

    /**
     * When set, [rasterize] issues a `glFinish` at every stage boundary so each stage is billed the GPU
     * work it actually caused (see [RasterizeTiming]). Costs a full pipeline stall per stage, so it is
     * for the profiling harness only — never set in production.
     */
    internal var profileSyncGl: Boolean = false

    /**
     * Runs [block], then (under [profileSyncGl]) drains the GL pipeline, and reports the elapsed nanos to
     * [sink]. Inline so the timing wrapper adds no allocation or call overhead to the measured stage.
     */
    private inline fun <T> timed(
        sink: (Long) -> Unit,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        val result = block()
        if (profileSyncGl) Gdx.gl.glFinish()
        sink(System.nanoTime() - start)
        return result
    }

    /**
     * Per-glyph shift-search results, keyed by the rasterise geometry (krogue-9x7.6). The search is a
     * deterministic function of the supersampled master, and for a fixed face that master is determined by
     * the cell size and the effective supersample — so a size this source has rasterised before can reuse
     * its winning offsets and skip the search outright. That is exactly the resize-heavy
     * `resolutionIndependent` case ADR-0037 called out: a window drag walks back over sizes it has already
     * seen. Access-ordered and capped at [SHIFT_CACHE_MAX_SIZES], so a long drag across hundreds of sizes
     * evicts the least recently used rather than growing without bound.
     */
    private val shiftCache =
        object : LinkedHashMap<Long, ShiftPlan>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ShiftPlan>): Boolean =
                size > SHIFT_CACHE_MAX_SIZES
        }

    /**
     * One rasterise geometry's winning shift offsets: [shifts]`[slot]` packs the sub-pixel x offset in the
     * high 16 bits and the y offset in the low 16 (the band-scale path warps the vertical resample rather
     * than translating it, so its y is always 0). Cell-filling slots take the edge-pinning warp instead of
     * a shift ([emitCellFillingCell]) and leave their entry at 0, unread. [bandScaled] records which
     * downsample produced the plan, so the two paths can never read each other's offsets for the same key.
     */
    private class ShiftPlan(
        val bandScaled: Boolean,
        val shifts: IntArray,
    )

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
     * is unavailable it falls back to rasterising directly at the cell px. The result is read back upright,
     * repacked with a per-cell gutter ([GlyphAtlasPadding]), and uploaded as the texture whose 256 cell
     * regions become [regions].
     */
    private fun rasterize(
        w: Int,
        h: Int,
    ) {
        val maxTex = maxTextureSize()
        // The PADDED page is what finally gets uploaded, and no amount of dropping supersample shrinks it
        // — so a cell too large for the GPU is a hard failure, checked before ANY of the arithmetic or
        // allocation below (krogue-y1o; mirrors TileSheetGlyphSource's own check). The gutter costs two px
        // per cell per axis, so the boundary sits COLUMNS*2 px below the tight page's. Long spans, so a
        // cell size large enough to wrap an Int product cannot slip under the limit as a negative.
        val pageW = GlyphAtlasPadding.pageSpanPx(w, COLUMNS)
        val pageH = GlyphAtlasPadding.pageSpanPx(h, ROWS)
        check(pageW <= maxTex && pageH <= maxTex) {
            "glyph atlas ${pageW}x$pageH exceeds GL_MAX_TEXTURE_SIZE ($maxTex) at a ${w}x$h cell " +
                "(a ${COLUMNS}x$ROWS page, each cell gutter-padded by ${GlyphAtlasPadding.GUTTER_PX}px); " +
                "use a smaller cell"
        }
        // Past the guard every span fits an Int comfortably, so the tight master's arithmetic is safe.
        val atlasW = w * COLUMNS
        val atlasH = h * ROWS
        // Supersample only if the linear-light downsampler is available; otherwise 1:1 at the cell px.
        var ss = if (supersample > 1 && ensureDownsampleShader()) supersample else 1
        // Cap so the whole-page master atlas stays within GL_MAX_TEXTURE_SIZE — a master that exceeds it
        // fails to allocate and renders garbage. Halve until it fits (keeps the 2:1 downsample exact).
        while (ss > 1 && (atlasW * ss > maxTex || atlasH * ss > maxTex)) {
            ss /= 2
        }
        effectiveSupersample = ss
        lastShiftSearchCount = 0
        val rasterizeStart = System.nanoTime()
        with(lastTiming) {
            fontGenNanos = 0L
            renderNanos = 0L
            halveNanos = 0L
            readbackNanos = 0L
            flipNanos = 0L
            downsampleNanos = 0L
            brightnessNanos = 0L
            padNanos = 0L
            uploadNanos = 0L
            totalNanos = 0L
        }

        // TEXT fit centres the font's *full ink box* (ascender-to-descender, incl. ring/accented caps) in
        // the cell (see [drawTextGlyph]). That box is taller than the em for typical faces, so rasterising
        // straight at the em (`h * ss`) would overflow the cell and the per-cell scissor would clip the
        // TOP of ascenders and accented caps — krogue-ux6 (the top-edge mirror of the krogue-ns5 descender
        // clip). Shrink the rasterised em so the ink box fits. TILE fit already scale-fits each glyph per
        // cell, so it keeps the full em — a smaller master would only blur its per-glyph upscale.
        val em = if (fit == GlyphFit.TEXT) textEmSize(h * ss) else h * ss
        val font = timed({ lastTiming.fontGenNanos = it }) { generateGlyphFont(em, ss) }

        // Each intermediate FBO / the font / the read-back pixmap is released even if a GL step throws
        // (an allocation or incomplete-FBO GdxRuntimeException) — the caller has no handle to these.
        val upright =
            try {
                preservingFrameBuffer {
                    // 1. Render every glyph centred in its ss-sized cell.
                    var buffer = FrameBuffer(Pixmap.Format.RGBA8888, atlasW * ss, atlasH * ss, false)
                    try {
                        timed({ lastTiming.renderNanos = it }) { renderGlyphs(buffer, font, w * ss, h * ss) }
                        if (snapToPixelGrid && ss > 1) {
                            // 2a. Per-glyph shift-search downsample (Brogue's optimizeTiles technique): read
                            // the supersampled master back and align each glyph to the output-pixel grid on
                            // the CPU (see shiftSearchDownsample). Bypasses the GPU halving passes.
                            // The read-back is left BOTTOM-UP and handed over as-is: the downsamplers index
                            // the master by hand, so they invert the row for free (krogue-5uw) rather than
                            // paying a whole flipY copy of a pixmap that is ss² times the atlas's area.
                            val master =
                                timed({ lastTiming.readbackNanos = it }) {
                                    buffer.begin()
                                    val px = Pixmap.createFromFrameBuffer(0, 0, atlasW * ss, atlasH * ss)
                                    buffer.end()
                                    px
                                }
                            try {
                                timed({ lastTiming.downsampleNanos = it }) {
                                    if (bandScale) {
                                        bandScaleDownsample(master, w, h, ss)
                                    } else {
                                        shiftSearchDownsample(master, w, h, ss)
                                    }
                                }
                            } finally {
                                master.dispose()
                            }
                        } else {
                            // 2b. Halve — gamma-correct, in linear light — until the atlas is at cell resolution.
                            timed({ lastTiming.halveNanos = it }) {
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
                            }
                            // 3. Read the cell-resolution atlas back, upright (FBO pixels are bottom-up).
                            val raw =
                                timed({ lastTiming.readbackNanos = it }) {
                                    buffer.begin()
                                    val px = Pixmap.createFromFrameBuffer(0, 0, atlasW, atlasH)
                                    buffer.end()
                                    px
                                }
                            try {
                                timed({ lastTiming.flipNanos = it }) { flipY(raw) }
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
        if (glyphBrightness > 1f) {
            timed({ lastTiming.brightnessNanos = it }) { applyPerGlyphBrightness(upright, w, h) }
        }

        // Repack the tight page with an extruded gutter around every cell before upload (krogue-wcw,
        // ADR-0044): the page is Linear-filtered, so a cell drawn at a magnifying scale samples past its
        // region edge, and in a tight page that is the neighbouring CP437 slot's ink. Everything above
        // works on the tight page — the per-cell scissor, the downsample strides, the brightness curve —
        // so the gutter is added once, here, where the atlas becomes a texture.
        val page =
            timed({ lastTiming.padNanos = it }) {
                try {
                    GlyphAtlasPadding.padded(upright, COLUMNS, ROWS, w, h)
                } finally {
                    upright.dispose()
                }
            }
        val newAtlas =
            timed({ lastTiming.uploadNanos = it }) {
                try {
                    Texture(page).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
                } finally {
                    page.dispose()
                }
            }

        atlas?.dispose()
        atlas = newAtlas
        regions =
            (0 until GLYPH_COUNT).map { slot ->
                TextureRegion(
                    newAtlas,
                    GlyphAtlasPadding.cellOriginPx(slot % COLUMNS, w),
                    GlyphAtlasPadding.cellOriginPx(slot / COLUMNS, h),
                    w,
                    h,
                )
            }
        charWidthPx = w
        charHeightPx = h
        lastTiming.totalNanos = System.nanoTime() - rasterizeStart
    }

    /**
     * Rasterises the whole CP437 page of this face at the given pixel [em] size (with [ss]-dependent
     * hinting). Factored out of [rasterize] so the TEXT em-fit ([textEmSize]) and the raw em share one
     * parameter block.
     */
    private fun generateGlyphFont(
        em: Int,
        ss: Int,
    ): BitmapFont =
        generator.generateFont(
            FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                size = em
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

    /**
     * The rasterised em size for a TEXT-fit cell [masterCellH] px tall (krogue-ux6): the em shrunk just
     * enough that the face's **full ink box** — cap height plus the ascent above it (the tallest glyph,
     * incl. ring/accented caps like Å/Ä/É) plus the descent below the baseline — fits within the cell, so
     * [drawTextGlyph] can centre that whole box without the per-cell scissor clipping ascender/accent
     * tops. A small top+bottom breathing margin ([TEXT_MARGIN_FRAC]) is held back so the tallest accent
     * doesn't sit flush against the ceiling and the deepest descender clears the floor (preserving
     * krogue-ns5). [faceInkBoxRatio] is the ink-box height as a fraction of the em (typically > 1). Never
     * returns < 1 and never larger than the em.
     */
    private fun textEmSize(masterCellH: Int): Int {
        val ratio = faceInkBoxRatio()
        // A face that reports no usable metrics gives a ratio of 0 (or negative); don't shrink then, and
        // never divide by it. (Not `coerceAtLeast(1f)`: a ratio in (0,1) means the ink box already fits the
        // em, so we must divide by the true ratio — clamping it to 1 would shrink a face that needs no shrink.)
        if (ratio <= 0f) return masterCellH
        val fitPx = masterCellH * (1f - 2f * TEXT_MARGIN_FRAC)
        // [textFill] spends the reserved headroom on glyph size; the ceiling rises with it, since the
        // point is to let the em exceed what the full ink box would allow.
        return (fitPx * textFill / ratio).toInt().coerceIn(1, (masterCellH * textFill).toInt().coerceAtLeast(1))
    }

    /**
     * Height of this face's full ink box (cap height + ascent above the cap + descent below the baseline)
     * as a fraction of the em, measured once from a small reference rasterisation and cached. Faces whose
     * ascender/descender exceed the em (most, once ring/accented-cap room is counted) return > 1, driving
     * the TEXT em-shrink in [textEmSize]. Uses the default character set (which includes the capitals
     * gdx-freetype measures `capHeight` from); the value is a face constant, independent of cell size.
     */
    private fun faceInkBoxRatio(): Float {
        cachedInkBoxRatio?.let { return it }
        val ref = generateGlyphFontMetricsOnly()
        val ratio =
            try {
                (ref.capHeight + ref.ascent.coerceAtLeast(0f) + abs(ref.descent)) / REF_METRIC_EM
            } finally {
                ref.dispose()
            }
        return ratio.also { cachedInkBoxRatio = it }
    }

    /** A cheap metrics-only rasterisation at [REF_METRIC_EM] (default chars, no page) for [faceInkBoxRatio]. */
    private fun generateGlyphFontMetricsOnly(): BitmapFont =
        generator.generateFont(
            FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                size = REF_METRIC_EM
                hinting = FreeTypeFontGenerator.Hinting.None
            },
        )

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
     *
     * **Except** for the *cell-filling* glyphs — box drawing and block/shade elements ([isCellFilling]) —
     * which both fits place by [drawCellFillingGlyph] instead: edge-snapped to the cell rect, so they tile
     * seamlessly across neighbouring cells at any cell aspect (krogue-9x7.4).
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
            // The face's design cell, measured from its full block (krogue-9x7.4); null if the face has no
            // inked █, in which case box drawing falls back to the ordinary per-fit placement.
            val designCell = measureDesignCell(font)
            for (slot in 0 until GLYPH_COUNT) {
                val codePoint = Cp437.toUnicode(slot)
                if (codePoint < 0) continue

                val col = slot % COLUMNS
                val row = slot / COLUMNS
                // Clip to this cell (GL scissor is bottom-left origin; the cell's bottom edge is
                // atlasH - (row+1)*h).
                Gdx.gl.glScissor(col * w, atlasH - (row + 1) * h, w, h)
                val drew =
                    when {
                        designCell != null && isCellFilling(codePoint) ->
                            drawCellFillingGlyph(font, batch, codePoint, col, row, w, h, atlasH, designCell)
                        fit == GlyphFit.TEXT ->
                            drawTextGlyph(font, batch, layout, codePoint, col, row, w, h, atlasH)
                        else -> drawTileGlyph(font, batch, codePoint, col, row, w, h, atlasH, tileEmScale)
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
     * **Ink-box fit (krogue-ns5 + krogue-ux6):** the cell is centred on the font's *full ink box* — the
     * ascent above the baseline (cap height plus the ascender/accent room above the cap, i.e. the tallest
     * glyph: ascenders `b d f h k l t`, ring/accented caps `Å Ä É`) and the descent below it — rather than
     * on the cap box alone. ns5 reserved the descent so `g/j/p/q/y` tails clear the floor; ux6 reserves the
     * full ascent (not just the cap) so those tall tops clear the ceiling. Both edges are the per-cell
     * scissor, so reserving both keeps every glyph inside its cell. The em is shrunk upstream ([textEmSize])
     * so this ink box fits the cell, giving `spare >= 0`. ascent, capHeight and descent are the same for
     * every glyph, so the baseline is identical across the whole page — the invariant the band-scale
     * downsample relies on ([bandScaleDownsample] measures the baseline once from the rendered `x`).
     * Returns `false` (nothing drawn) for a glyph with no ink, e.g. space.
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
        // Centre the full ink box (ascent + descent) in the cell; BitmapFont.draw takes y at the cap line,
        // so baseline = drawY - capHeight. Reserve `ascentPx` above the baseline (cap + ascender/accent
        // room, so tall tops clear the ceiling — ux6) and `descentPx` below it (so tails clear the floor —
        // ns5). font.ascent is the room ABOVE the cap; font.descent is negative (below baseline), hence abs.
        val capHeight = font.capHeight
        val ascentPx = capHeight + font.ascent.coerceAtLeast(0f)
        val descentPx = abs(font.descent)
        val cellBottom = (atlasH - (row + 1) * h).toFloat()
        val spare = (h - (ascentPx + descentPx)).coerceAtLeast(0f)
        val baseline = cellBottom + descentPx + spare / 2f
        font.draw(batch, layout, drawX, baseline + capHeight)
        return true
    }

    /**
     * [GlyphFit.TILE] placement: draw [codePoint]'s rendered **ink box** directly, ink-centred in the
     * cell at the page-wide [emScale] (uniform enlargement — see [GlyphFit.TILE]), clamped down per glyph
     * so nothing overflows its cell. The uniform scale keeps relative glyph sizes (a period stays a small
     * dot; a capital fills), while the clamp lets a tall glyph fill exactly rather than overflow-and-clip.
     * The glyph's page region comes from [glyphRegion] (no extra V-flip — see there). Returns `false` when
     * the face has no inked glyph for the slot (e.g. space, or a missing glyph).
     *
     * Cell-*filling* glyphs (box drawing, blocks, shades) never reach here: ink-centring leaves them short
     * of the cell edge on whichever axis doesn't bind, so they are edge-snapped by [drawCellFillingGlyph]
     * instead (krogue-9x7.4).
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
        val drawX = (col * w) + (w - drawW) / 2f
        val drawY = (atlasH - (row + 1) * h) + (h - drawH) / 2f // y-up: cell's bottom edge + vertical centre
        batch.draw(glyphRegion(font, glyph), drawX, drawY, drawW, drawH)
        return true
    }

    /**
     * Is [codePoint] a **cell-filling** glyph — one the face draws to the edges of its design cell so that
     * neighbouring cells join (krogue-9x7.4)?
     *
     * The class is Unicode's two cell-filling blocks: **Box Drawing** `U+2500–U+257F` (`─ │ ┼ ╔ ╣ …`) and
     * **Block Elements** `U+2580–U+259F` (`█ ▀ ▄ ▌ ▐`, the shades `░ ▒ ▓`). In CP437 that is exactly slots
     * 176–223 (`0xB0–0xDF`). Deliberately *not* included: glyphs that merely look blocky but are centred
     * ornaments the face never meant to tile — `■ U+25A0` (slot 254), `▬ U+25AC` (slot 22), the arrows and
     * triangles — which keep the ordinary ink-centred placement.
     *
     * Members are placed by [drawCellFillingGlyph] and are exempt from the [snapToPixelGrid] sub-pixel
     * shift search and band-scale warp, both of which would pull ink off a pinned cell edge (see [emitCell]).
     * They get their own alignment instead — [emitCellFillingCell]'s edge-pinning warp, which snaps the
     * *stroke* edges inside the cell without moving the cell edges.
     */
    private fun isCellFilling(codePoint: Int): Boolean =
        codePoint in BOX_DRAWING_FIRST..BLOCK_ELEMENTS_LAST || codePoint in boxAlignedGlyphs

    /** [isCellFilling] by CP437 slot — the form the per-slot downsample loops need. */
    private fun isCellFillingSlot(slot: Int): Boolean = isCellFilling(Cp437.toUnicode(slot))

    /**
     * The face's **design cell**: the rect a cell-filling glyph occupies in the font, in the pen-relative
     * y-up frame `BitmapFont.Glyph` metrics use (x from the pen, y up from the draw line; see
     * [drawCellFillingGlyph]). Measured from the rendered **full block** U+2588 — the one glyph that is,
     * by definition, exactly the design cell — rather than assumed from `advance`/`lineHeight`, so it
     * self-calibrates to whatever box the face actually draws its block/box elements in.
     */
    private class DesignCell(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    /**
     * Measures this face's [DesignCell] from its full block (U+2588), or `null` if the face has no inked
     * block glyph — in which case the cell-filling placement is skipped entirely and box drawing keeps the
     * ordinary per-[fit] placement (the pre-krogue-9x7.4 behaviour). Both bundled faces have it.
     *
     * What `Glyph` reports is the **rasterised bitmap** box, which FreeType expands to whole master pixels
     * — so up to one master pixel per side is *antialiasing fringe* rather than solid ink (whenever the
     * block's outline edge doesn't land on a master pixel boundary, which vertically it generally doesn't:
     * the ascent/descent are not integers). Mapping that box onto the cell would put the fringe, not the
     * solid edge, at the cell boundary, and the outermost output pixel would come out short of full ink —
     * measured at ~85% on Cascadia Mono's top/bottom, i.e. a faint lattice along the joins instead of a
     * clean one. So the box is inset by [FRINGE_MASTER_PX] per side: the **solid** part maps to the cell
     * and the fringe spills just past it, where the per-cell scissor drops it. What spills is at most one
     * master pixel — a fraction of an output pixel — off a flat run, which is exactly the part of a
     * cell-filling glyph that carries no detail.
     */
    private fun measureDesignCell(font: BitmapFont): DesignCell? {
        val block = font.data.getGlyph(FULL_BLOCK) ?: return null
        if (block.width <= 0 || block.height <= 0) return null
        // Never inset a box away to nothing (a degenerate//tiny block glyph): keep at least one master px.
        val inset = 2 * FRINGE_MASTER_PX
        val w = (block.width - inset).coerceAtLeast(1f)
        val h = (block.height - inset).coerceAtLeast(1f)
        return DesignCell(
            block.xoffset + (block.width - w) / 2f,
            block.yoffset + (block.height - h) / 2f,
            w,
            h,
        )
    }

    /**
     * **Cell-filling placement (krogue-9x7.4)** — how box-drawing and block/shade glyphs ([isCellFilling])
     * are placed under *both* fits, so they tile seamlessly.
     *
     * These glyphs are cell-*filling* in the face: `─` spans the whole design cell horizontally, `│`
     * vertically, `█` both, `▄` its bottom half. Adjacent cells only join if each glyph's ink reaches the
     * cell edge — which ink-centring (the [GlyphFit.TILE] scale-fit) and baseline text layout
     * ([drawTextGlyph], whose em is shrunk to reserve ascender/descender room) both fail to do whenever the
     * target cell's aspect differs from the face's `advance : line-height`: the glyph is scaled by whichever
     * axis binds, leaving a gap on the other. That is the seam this placement closes.
     *
     * So instead of centring, the face's [DesignCell] (from `█`) is mapped **affinely onto the whole cell
     * rect** — independently per axis — and every cell-filling glyph is drawn through that same map. Edges
     * therefore land on cell edges (`█` fills exactly, `▄` is exactly the bottom half, `├`'s arm ends flush
     * at the right edge), and because neighbouring cells share the map, strokes meet with matching position
     * and weight. The cost of an aspect mismatch moves from a *gap* to a *stroke-weight* difference between
     * the two axes, which is the trade the issue asks for. A glyph whose ink overshoots the design cell
     * (some faces overshoot deliberately) simply spills and is clipped by the per-cell scissor — still
     * seamless.
     *
     * `Glyph.xoffset`/`yoffset` are the ink box's left edge and its *bottom* edge relative to the pen, in
     * libGDX's y-up draw frame (`BitmapFontCache.addGlyph` places the quad at `y + yoffset` and spans
     * upward from there) — the same frame the block was measured in, so the shared pen origin cancels and
     * only differences from the design cell matter. Returns `false` when the face has no inked glyph for
     * the slot.
     */
    private fun drawCellFillingGlyph(
        font: BitmapFont,
        batch: SpriteBatch,
        codePoint: Int,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        atlasH: Int,
        cell: DesignCell,
    ): Boolean {
        // codePoint is always in the BMP here (every Cp437 entry is < U+FFFF), so a single Char indexes it.
        val glyph = font.data.getGlyph(codePoint.toChar()) ?: return false
        if (glyph.width == 0 || glyph.height == 0) return false // no ink (or a missing glyph)

        // A true cell-filling glyph is stretched to the cell on both axes: that is what makes its strokes
        // meet its neighbours' at the shared edge, and a line stretched along its own length is still the
        // same line. A glyph merely *aligned* to this grid ([boxAlignedGlyphs]) has a shape to preserve,
        // and the design cell is far from square — a tall advance-by-line-height box — so stretching it to
        // a square cell would visibly squash it (a `+` came out 2.33:1). Such a glyph therefore takes the
        // vertical scale on both axes, which is the axis that decides whether it lands on the box grid,
        // and is centred across the cell rather than positioned from the design cell's left edge.
        val scaleY = h / cell.h
        val uniform = codePoint !in BOX_DRAWING_FIRST..BLOCK_ELEMENTS_LAST
        val scaleX = if (uniform) scaleY else w / cell.w
        val drawX =
            if (uniform) {
                (col * w) + (w - glyph.width * scaleX) / 2f
            } else {
                (col * w) + (glyph.xoffset - cell.x) * scaleX
            }
        val drawY = (atlasH - (row + 1) * h) + (glyph.yoffset - cell.y) * scaleY // y-up from the cell's floor
        batch.draw(glyphRegion(font, glyph), drawX, drawY, glyph.width * scaleX, glyph.height * scaleY)
        return true
    }

    /**
     * [glyph]'s slice of its [font] page as a drawable region. Drawn with **no extra V-flip**: BitmapFont
     * already stores its pages oriented for a y-up batch (which is why the [GlyphFit.TEXT] `font.draw` path
     * lands upright through the same downstream passes), so a raw region draw matches it as-is; flipping
     * here would render every glyph upside-down.
     */
    private fun glyphRegion(
        font: BitmapFont,
        glyph: BitmapFont.Glyph,
    ): TextureRegion =
        TextureRegion(font.getRegion(glyph.page).texture, glyph.srcX, glyph.srcY, glyph.width, glyph.height)

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
     * Byte-buffer index of the first pixel of **upright** row [uprightRow] in a [masterW]x[masterH]
     * **bottom-up** master.
     *
     * An FBO read-back is stored bottom-up, and the snap path used to spend a whole `flipY` copy of the
     * *supersampled* master (a 3072x3072 RGBA pixmap at a 48px cell) just to correct that — 26–38% of a
     * rasterise, per the krogue-4jh profile. Its only consumers index the master by hand, so krogue-5uw
     * keeps the read-back in its native orientation and inverts the row here instead: one subtraction
     * per row, no copy and no allocation. This is the single place the orientation is handled — every
     * caller below thinks in upright rows.
     */
    private fun bottomUpRowStart(
        uprightRow: Int,
        masterW: Int,
        masterH: Int,
    ): Int = (masterH - 1 - uprightRow) * masterW

    /**
     * Fills [sat] (size `(mW+1)·(mH+1)`, cleared first) with the per-cell summed-area table of master
     * alpha for the cell at **upright** master origin ([mx0], [my0]) in the [masterW]x[masterH] [buf]:
     * `sat[y·(mW+1)+x] = Σ alpha over master `[0,x) × [0,y)``, so any axis-aligned box average over the
     * cell is O(1). Shared by [shiftSearchDownsample] and [bandScaleDownsample].
     *
     * [buf] is the **bottom-up** read-back (see [bottomUpRowStart]); `sat` comes out in upright rows, so
     * everything downstream of it is orientation-free.
     */
    private fun buildCellSat(
        buf: java.nio.ByteBuffer,
        sat: IntArray,
        masterW: Int,
        masterH: Int,
        mx0: Int,
        my0: Int,
        mW: Int,
        mH: Int,
    ) {
        val satW = mW + 1
        java.util.Arrays.fill(sat, 0)
        for (my in 0 until mH) {
            val srcBase = bottomUpRowStart(my0 + my, masterW, masterH) + mx0
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
     * The [shiftCache] key for a rasterise geometry: cell [w] x [h] at effective supersample [ss]. Packed
     * into a Long rather than boxing a triple — cell dimensions are bounded by `GL_MAX_TEXTURE_SIZE / ss`
     * and the supersample by 8, so all three fit their fields with room to spare.
     */
    private fun shiftKey(
        w: Int,
        h: Int,
        ss: Int,
    ): Long = (w.toLong() shl 40) or (h.toLong() shl 16) or ss.toLong()

    /**
     * The cached winning offsets for rasterise geometry [key], or `null` if this size has not been searched
     * yet (or was searched by the *other* downsample path — [bandScaled] must match, so the band-scale
     * fallback to [shiftSearchDownsample] cannot pick up band-scaled offsets or vice versa).
     */
    private fun cachedShifts(
        key: Long,
        bandScaled: Boolean,
    ): IntArray? = shiftCache[key]?.takeIf { it.bandScaled == bandScaled }?.shifts

    /**
     * Per-glyph sub-pixel **shift-search** downsample — the crispness core of [snapToPixelGrid]. A Kotlin
     * re-implementation of the *technique* in **Brogue CE** (`tmewett/BrogueCE`, `src/platform/tiles.c`,
     * `optimizeTiles`/`downscaleTile`). Brogue is **AGPL-3.0**; kotile is **BSD-3-Clause**. This is
     * independent original code (a summed-area table, not Brogue's per-candidate accumulation) expressing a
     * non-copyrightable method — **no Brogue code is copied**, so it does not trigger AGPL. The credit is
     * provenance, not a licence grant. See docs/adr/0037 for the full reasoning.
     *
     * For each CP437 cell of the supersampled [master] (white glyph, coverage in alpha) it tries a grid
     * of sub-pixel offsets, box-downsamples the master cell to [w]x[h] at each, and keeps the offset that
     * **minimises Brogue's blur metric** `Σ sin(π·coverage)` — the sum is smallest when the fewest pixels
     * are half-lit (grey-edged), i.e. when stems land squarely on output pixels. A per-cell summed-area
     * table makes each box average O(1), so the whole search is one CPU pass rather than [ss]² GL
     * readbacks. Coverage is straight-averaged, matching [GammaDownsample]'s alpha handling.
     *
     * [master] is the raw **bottom-up** FBO read-back — the row inversion happens for free inside
     * [buildCellSat] (krogue-5uw) — and the returned cell-resolution atlas (white RGB, aligned alpha) is
     * **upright**.
     *
     * The winning offsets are memoised in [shiftCache] under this geometry (krogue-9x7.6): a repeat of a
     * cell size skips the search and goes straight to [emitCell] with the offsets it won last time.
     */
    private fun shiftSearchDownsample(
        master: Pixmap,
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
        val masterW = master.width
        val masterH = master.height
        val buf = master.pixels // RGBA8888 ByteBuffer; alpha is the 4th byte of each pixel
        val ssArea = (ss * ss).toFloat()
        val step = (ss / 4).coerceAtLeast(1) // sub-pixel search resolution: quarter of an output pixel
        val satW = mW + 1
        val sat = IntArray(satW * (mH + 1)) // reused per-cell summed-area table of master alpha
        // A previously rasterised cell size already knows every glyph's winning offset (krogue-9x7.6); on a
        // miss the same array is filled as we go and published to the cache below.
        val key = shiftKey(w, h, ss)
        val cached = cachedShifts(key, bandScaled = false)
        val shifts = cached ?: IntArray(GLYPH_COUNT)

        for (slot in 0 until GLYPH_COUNT) {
            val col = slot % COLUMNS
            val row = slot / COLUMNS
            val mx0 = col * mW
            val my0 = row * mH

            buildCellSat(buf, sat, masterW, masterH, mx0, my0, mW, mH)

            // Cell-filling glyphs sit out the translation search — it would slide ink off a pinned cell
            // edge — and get the edge-pinning stroke warp instead (see [emitCellFillingCell]).
            if (isCellFillingSlot(slot)) {
                emitCellFillingCell(out, sat, col, row, w, h, ss)
                continue
            }

            if (cached == null) {
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
                shifts[slot] = (bestSx shl 16) or bestSy
                lastShiftSearchCount++
            }

            val packed = shifts[slot]
            emitCell(out, sat, col, row, w, h, ss, packed ushr 16, packed and 0xFFFF)
        }
        if (cached == null) shiftCache[key] = ShiftPlan(bandScaled = false, shifts = shifts)
        return out
    }

    /**
     * Emits one cell of the output atlas [out] at grid ([col], [row]) by box-averaging the per-cell
     * summed-area table [sat] at the sub-pixel offset ([sx], [sy]) — the shared emit step of the
     * [snapToPixelGrid] downsamples (white RGB, straight-averaged alpha; a zero-alpha pixel is left
     * untouched so blank cells stay transparent per ADR-0029). The offset shifts the sampling window, so
     * the trailing output pixel's box is clamped at the master edge and loses a proportional slice of its
     * coverage: correct for a glyph that floats inside its cell, and exactly why cell-filling glyphs must
     * be emitted at offset (0, 0) — see [isCellFilling].
     */
    private fun emitCell(
        out: Pixmap,
        sat: IntArray,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        ss: Int,
        sx: Int,
        sy: Int,
    ) {
        val mW = w * ss
        val mH = h * ss
        val satW = mW + 1
        val ssArea = (ss * ss).toFloat()
        val ox0 = col * w
        val oy0 = row * h
        for (oy in 0 until h) {
            val y0 = (oy * ss + sy).coerceIn(0, mH)
            val y1 = (oy * ss + sy + ss).coerceIn(0, mH)
            val ry0 = y0 * satW
            val ry1 = y1 * satW
            for (ox in 0 until w) {
                val x0 = (ox * ss + sx).coerceIn(0, mW)
                val x1 = (ox * ss + sx + ss).coerceIn(0, mW)
                val sum = sat[ry1 + x1] - sat[ry0 + x1] - sat[ry1 + x0] + sat[ry0 + x0]
                val a = (sum / ssArea).roundToInt().coerceIn(0, 255)
                if (a != 0) out.drawPixel(ox0 + ox, oy0 + oy, 0xFFFFFF00.toInt() or a) // white RGB + aligned alpha
            }
        }
    }

    /**
     * Emits one cell-filling glyph's cell (box drawing, blocks — [isCellFilling]) under [snapToPixelGrid]
     * with an **edge-pinning stroke warp** (krogue-tg5): the cell edges stay pinned to the cell rect, while
     * the glyph's *interior* stroke edges are snapped to whole output rows/columns.
     *
     * This is ADR-0038's band-scale idea — pin two references and stretch between them — applied per axis
     * to a box glyph's stroke edges instead of the x-height band. krogue-9x7.4 had to exempt this class from
     * both [snapToPixelGrid] transforms, because a *translation* (the shift search) or a baseline-relative
     * warp (the band scale) slides the sampling window and the master-edge clamp then shaves the trailing
     * output pixel, re-opening the seam the class exists to close. A warp that pins `out 0 → master 0` and
     * `out n → master n` has no such clamp: it only redistributes rows *between* the cell edges, so the
     * seams survive by construction and a 1-output-pixel-and-a-half stroke stops straddling two rows and
     * reading grey.
     *
     * Per axis: [strokeSnapMap] measures the stroke edges from this cell's own master (they are per-glyph,
     * unlike the page-wide text baseline), rounds each to its nearest output boundary, and returns the
     * piecewise-linear output→master map through those knots; a `null` means "nothing to snap" (a uniform
     * axis like `─`'s horizontal, or a pattern with too many edges — the shades) and that axis keeps its
     * natural slope. With **both** axes natural the map is the identity, so it defers to [emitCell] at
     * offset `(0, 0)` — byte-for-byte the pre-tg5 emit, which is what keeps `█`/`│`/`─`'s measured seam
     * numbers unchanged.
     *
     * Neighbouring cells still join because the snap is a deterministic function of the master position of
     * the stroke, and the cell-filling placement gives every member of the class the *same* master stroke
     * positions ([drawCellFillingGlyph] maps one design cell onto every cell): `─`'s stroke and `┼`'s
     * cross-bar snap to the same output rows. And a warp that is monotone with both ends pinned leaves a
     * *uniform* run uniform, so an axis one glyph warps and its neighbour doesn't (`┬`'s stem column vs `─`'s
     * flat horizontal) still matches along the shared edge.
     */
    private fun emitCellFillingCell(
        out: Pixmap,
        sat: IntArray,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        ss: Int,
    ) {
        val mW = w * ss
        val mH = h * ss
        val srcX = strokeSnapMap(sat, mW, mH, w, ss, vertical = false)
        val srcY = strokeSnapMap(sat, mW, mH, h, ss, vertical = true)
        if (srcX == null && srcY == null) {
            emitCell(out, sat, col, row, w, h, ss, 0, 0)
            return
        }
        emitWarpedCell(
            out,
            sat,
            col,
            row,
            w,
            h,
            mW,
            mH,
            srcX ?: naturalMap(w, ss),
            srcY ?: naturalMap(h, ss),
        )
    }

    /** The unwarped output→master boundary map for an axis of [outN] output pixels at [ss]× supersampling. */
    private fun naturalMap(
        outN: Int,
        ss: Int,
    ): DoubleArray = DoubleArray(outN + 1) { (it * ss).toDouble() }

    /**
     * The output→master boundary map for one axis of a cell-filling glyph, with the glyph's stroke edges
     * snapped to whole output boundaries and the two cell edges pinned — or `null` when there is nothing to
     * snap on this axis, in which case the caller keeps the natural slope ([naturalMap]).
     *
     * `null` covers three cases, all of which must fall back rather than warp: an empty cell; a **uniform**
     * axis (`─` along x, `█` along either — a single run spanning the whole extent yields no *interior*
     * edge); and a cell-filling **pattern** (the shades `░ ▒ ▓`), whose many edges exceed
     * [MAX_STROKE_EDGES] — snapping a periodic dither is neither needed nor well-defined, so it keeps the
     * uniform resample it had before.
     */
    private fun strokeSnapMap(
        sat: IntArray,
        mW: Int,
        mH: Int,
        outN: Int,
        ss: Int,
        vertical: Boolean,
    ): DoubleArray? {
        val n = if (vertical) mH else mW
        val edges = strokeEdges(axisProfile(sat, mW, mH, vertical)) ?: return null
        return strokeSnapMapFrom(edges, outN, ss, n)
    }

    /** [strokeSnapMap]'s tail, for callers that already measured [edges] (and may reuse their count). */
    private fun strokeSnapMapFrom(
        edges: StrokeEdges,
        outN: Int,
        ss: Int,
        n: Int,
    ): DoubleArray? {
        val outK = snapEdgesToOutput(edges, outN, ss) ?: return null
        return piecewiseMap(edges.at, outK, outN, n)
    }

    /**
     * The coverage profile of a cell across one axis, read straight off its summed-area table [sat]: total
     * master alpha per master row ([vertical]) or per master column. A horizontal stroke shows up as a run
     * of near-full rows; the thin ink a crossing stem contributes to the rows between strokes is a small
     * fraction of that, which is what makes a half-peak threshold separate them ([strokeEdges]).
     */
    private fun axisProfile(
        sat: IntArray,
        mW: Int,
        mH: Int,
        vertical: Boolean,
    ): DoubleArray {
        val satW = mW + 1
        if (vertical) {
            return DoubleArray(mH) { my -> (sat[(my + 1) * satW + mW] - sat[my * satW + mW]).toDouble() }
        }
        val lastRow = mH * satW
        return DoubleArray(mW) { mx -> (sat[lastRow + mx + 1] - sat[lastRow + mx]).toDouble() }
    }

    /**
     * The **interior** stroke edges of one axis: their fractional master positions [at], and for each,
     * whether it closes a stroke whose opening edge is the previous entry ([closesStroke]) — the pairing
     * [snapEdgesToOutput] needs to round a stroke's *width* rather than its two edges independently.
     */
    private class StrokeEdges(
        val at: DoubleArray,
        val closesStroke: BooleanArray,
    )

    /**
     * The **interior** stroke edges of a coverage profile [p], or `null` if there is nothing snappable
     * (see [strokeSnapMap]).
     *
     * A stroke is a run of samples at or above half the profile's own peak — Brogue's rule for the x-height
     * band ([measureXBand]) applied across the cell — and its two edges are that run's boundaries. The
     * threshold is peak-relative, not absolute, so a stroke that spans only part of the cell (`├`'s arm
     * reaches ~60% of the width, `─`'s the whole width) still resolves against its own glyph, and both then
     * snap to the same rows because the *shape* of the antialiasing across the edge is the same.
     *
     * Each boundary is refined below one master pixel by conserving ink: an edge inside sample `a` leaves
     * `a` covered `p[a] / peak`, so the edge sits that far into the sample. Without the refinement the edge
     * would be quantised to a whole master pixel — a quarter of an output pixel at the default 4×
     * supersample, enough to round to the wrong output row. Runs touching the cell boundary contribute only
     * their inner edge; the cell edges are pinned by the caller and must not become knots (and such a run
     * has no interior *width*, so its edge is never paired).
     */
    private fun strokeEdges(p: DoubleArray): StrokeEdges? {
        val n = p.size
        var peak = 0.0
        for (v in p) if (v > peak) peak = v
        if (peak <= 0.0) return null
        val threshold = peak / 2.0
        val at = ArrayList<Double>()
        val closes = ArrayList<Boolean>()
        var i = 0
        while (i < n) {
            if (p[i] < threshold) {
                i++
                continue
            }
            var b = i
            while (b + 1 < n && p[b + 1] >= threshold) b++
            val opened = i > 0
            if (opened) {
                at += i + (1.0 - p[i] / peak) - p[i - 1] / peak
                closes += false
            }
            if (b < n - 1) {
                at += (b + 1) - (1.0 - p[b] / peak) + p[b + 1] / peak
                closes += opened
            }
            if (at.size > MAX_STROKE_EDGES) return null
            i = b + 1
        }
        if (at.isEmpty()) return null
        // Two edges closer than half a master pixel would make a zero- or negative-height band; that is a
        // stroke too thin for this to be meaningful, so keep the uniform resample rather than warping.
        for (k in 1 until at.size) {
            if (at[k] - at[k - 1] < MIN_EDGE_GAP_MASTER) return null
        }
        return StrokeEdges(at.toDoubleArray(), closes.toBooleanArray())
    }

    /**
     * Places each stroke edge on a whole output boundary — the knots the warp pins ([piecewiseMap]).
     *
     * A stroke's **opening** edge goes to its nearest boundary (ADR-0038's `round(map2)`); its **closing**
     * edge is then placed a *rounded width* away, rather than at its own nearest boundary. Rounding the two
     * edges independently would quantise the width to `round(e1) - round(e0)`, which differs from the true
     * width by up to a whole output pixel: measured on Cascadia Mono at a square 24px cell, `│`'s 5.35px
     * stem landed in a 6px band and every column came out at 89% — a stroke made *greyer* by the snap it
     * was supposed to sharpen. Rounding the width instead keeps the band's average coverage at the stroke's
     * own density (5.35px of ink in 5px reads solid), which is the point of snapping: turn partial coverage
     * spread over two pixels into whole pixels, never dilute it across more.
     *
     * Knots are then forced strictly increasing and strictly inside `(0, outN)`, so neither a stroke nor a
     * cell edge can collapse — a stroke narrower than one output pixel becomes exactly one rather than
     * vanishing. Returns `null` if they cannot be separated in the space available (more strokes than
     * output pixels), in which case the axis keeps its natural slope.
     */
    private fun snapEdgesToOutput(
        edges: StrokeEdges,
        outN: Int,
        ss: Int,
    ): IntArray? {
        val at = edges.at
        val k = IntArray(at.size)
        for (i in at.indices) {
            k[i] =
                if (edges.closesStroke[i]) {
                    k[i - 1] + Math.round((at[i] - at[i - 1]) / ss).toInt().coerceAtLeast(1)
                } else {
                    Math.round(at[i] / ss).toInt()
                }
        }
        var prev = 0
        for (i in k.indices) {
            if (k[i] <= prev) k[i] = prev + 1
            prev = k[i]
        }
        var next = outN
        for (i in k.indices.reversed()) {
            if (k[i] >= next) k[i] = next - 1
            next = k[i]
        }
        if (k.first() < 1) return null
        for (i in 1 until k.size) if (k[i] <= k[i - 1]) return null
        return k
    }

    /**
     * The piecewise-linear output→master boundary map through the knots `(0, 0)`, `(outK[i], edges[i])…`,
     * `(outN, n)`: `src[o]` is the master coordinate of output boundary `o`. Each segment gets its own
     * constant slope, so the natural scale is preserved except where a stroke had to move to land on the
     * grid — and both cell edges are exact, which is what keeps the seams closed.
     */
    private fun piecewiseMap(
        edges: DoubleArray,
        outK: IntArray,
        outN: Int,
        n: Int,
    ): DoubleArray {
        val src = DoubleArray(outN + 1)
        var o0 = 0
        var s0 = 0.0
        for (i in 0..edges.size) {
            val o1 = if (i < edges.size) outK[i] else outN
            val s1 = if (i < edges.size) edges[i] else n.toDouble()
            val slope = (s1 - s0) / (o1 - o0)
            for (o in o0..o1) src[o] = s0 + (o - o0) * slope
            o0 = o1
            s0 = s1
        }
        src[0] = 0.0
        src[outN] = n.toDouble()
        return src
    }

    /**
     * Emits one cell by box-averaging the master over the warped boundary maps [srcX] / [srcY] — output
     * pixel `(ox, oy)` covers the master rect `[srcX[ox], srcX[ox+1]] × [srcY[oy], srcY[oy+1]]`, whose
     * corners are fractional on **both** axes (where [emitCell] has an integer box and [bandScaleDownsample]
     * a fractional y-band only). Bilinear interpolation of the summed-area table is *exact* for that rect —
     * the master is piecewise-constant per pixel, so the SAT is bilinear within a pixel — which is what lets
     * the warp reuse the SAT rather than re-integrating the master. Per output row the two interpolated
     * boundary prefixes are computed once, so each pixel is one subtraction, as elsewhere.
     */
    private fun emitWarpedCell(
        out: Pixmap,
        sat: IntArray,
        col: Int,
        row: Int,
        w: Int,
        h: Int,
        mW: Int,
        mH: Int,
        srcX: DoubleArray,
        srcY: DoubleArray,
    ) {
        val satW = mW + 1
        val ox0 = col * w
        val oy0 = row * h
        val lo = DoubleArray(w + 1) // SAT at (srcX[i], band top)
        val hi = DoubleArray(w + 1) // SAT at (srcX[i], band bottom)
        for (oy in 0 until h) {
            val bandH = srcY[oy + 1] - srcY[oy]
            if (bandH <= 0.0) continue
            for (i in 0..w) {
                lo[i] = satBilinear(sat, satW, mW, mH, srcX[i], srcY[oy])
                hi[i] = satBilinear(sat, satW, mW, mH, srcX[i], srcY[oy + 1])
            }
            for (ox in 0 until w) {
                val area = (srcX[ox + 1] - srcX[ox]) * bandH
                if (area <= 0.0) continue
                val sum = (hi[ox + 1] - lo[ox + 1]) - (hi[ox] - lo[ox])
                val a = (sum / area).roundToInt().coerceIn(0, 255)
                if (a != 0) out.drawPixel(ox0 + ox, oy0 + oy, 0xFFFFFF00.toInt() or a)
            }
        }
    }

    /**
     * The summed-area table [sat] sampled at a fractional master position ([x], [y]) by bilinear
     * interpolation — i.e. the exact integral of master alpha over `[0, x) × [0, y)`. Exact rather than
     * approximate because the master is constant within each pixel, which makes its integral bilinear in
     * the sub-pixel offsets, matching what the interpolation computes. Positions are clamped into the cell.
     */
    private fun satBilinear(
        sat: IntArray,
        satW: Int,
        mW: Int,
        mH: Int,
        x: Double,
        y: Double,
    ): Double {
        val xc = x.coerceIn(0.0, mW.toDouble())
        val yc = y.coerceIn(0.0, mH.toDouble())
        val x0 = xc.toInt().coerceIn(0, mW - 1)
        val y0 = yc.toInt().coerceIn(0, mH - 1)
        val fx = xc - x0
        val fy = yc - y0
        val i00 = y0 * satW + x0
        val i10 = i00 + satW
        val top = sat[i00] + (sat[i00 + 1] - sat[i00]) * fx
        val bottom = sat[i10] + (sat[i10 + 1] - sat[i10]) * fx
        return top + (bottom - top) * fy
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
     * back to the uniform [shiftSearchDownsample]. [master] is the raw **bottom-up** FBO read-back (the
     * row inversion lives in [buildCellSat] / [measureXBand], krogue-5uw); the returned cell-resolution
     * atlas is **upright**.
     */
    private fun bandScaleDownsample(
        master: Pixmap,
        w: Int,
        h: Int,
        ss: Int,
    ): Pixmap {
        val mW = w * ss
        val mH = h * ss
        val xBand = measureXBand(master, mW, mH) ?: return shiftSearchDownsample(master, w, h, ss)

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
        val masterW = master.width
        val masterH = master.height
        val buf = master.pixels
        val step = (ss / 4).coerceAtLeast(1) // horizontal sub-pixel search resolution: quarter of a pixel
        val sxs = (0 until ss step step).toList()
        val satW = mW + 1
        val sat = IntArray(satW * (mH + 1)) // reused per-cell summed-area table of master alpha
        // rowBand[oy*(mW+1) + x] = Σ master alpha over cols [0,x) × the fractional y-band of output row oy.
        // A horizontal prefix, so a box average over [x0,x1) is one subtraction. Rebuilt per cell.
        val rowBand = DoubleArray(h * satW)
        val blur = DoubleArray(sxs.size)
        // Same size-keyed memoisation as the translation path (krogue-9x7.6); here only the x offset is
        // searched, so the packed y half is always 0. The vertical band map is shared by every cell and
        // cheap to recompute, so it is not cached — only the per-glyph search is.
        val key = shiftKey(w, h, ss)
        val cached = cachedShifts(key, bandScaled = true)
        val shifts = cached ?: IntArray(GLYPH_COUNT)

        for (slot in 0 until GLYPH_COUNT) {
            val col = slot % COLUMNS
            val row = slot / COLUMNS
            val mx0 = col * mW
            val my0 = row * mH

            buildCellSat(buf, sat, masterW, masterH, mx0, my0, mW, mH)

            // Cell-filling glyphs sit out both the band warp and the shift search: their cell edges are
            // already pinned to the cell rect, and either transform would pull ink off one of them — see
            // [isCellFilling]. They get the edge-pinning stroke warp instead ([emitCellFillingCell]), which
            // snaps their *interior* stroke edges while leaving the cell edges where they are.
            if (isCellFillingSlot(slot)) {
                emitCellFillingCell(out, sat, col, row, w, h, ss)
                continue
            }

            fillRowBand(sat, rowBand, vy0, vy1, mW, mH, h)

            if (cached == null) {
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
                shifts[slot] = sxs[bestI] shl 16
                lastShiftSearchCount++
            }
            val bestSx = shifts[slot] ushr 16

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
        if (cached == null) shiftCache[key] = ShiftPlan(bandScaled = true, shifts = shifts)
        return out
    }

    /**
     * Measures the x-height band of the rendered master by scanning the `x` glyph's cell (CP437 slot
     * [X_SLOT]): returns the top and bottom inked master rows (cell-local) at ≥ half the cell's peak
     * coverage, or `null` if `x` has no ink. Mirrors Brogue's definition of the text band from the `x`
     * outline. The half-peak threshold locks onto the solid stroke rather than faint anti-aliased tails,
     * so the measured band matches the visible x-height. Since [drawTextGlyph] shares a baseline across
     * all glyphs, this one measurement fixes the band for the whole page.
     *
     * [master] is the **bottom-up** read-back; the returned rows are cell-local **upright** rows (see
     * [bottomUpRowStart]).
     */
    private fun measureXBand(
        master: Pixmap,
        mW: Int,
        mH: Int,
    ): Pair<Int, Int>? {
        val col = X_SLOT % COLUMNS
        val row = X_SLOT / COLUMNS
        val mx0 = col * mW
        val my0 = row * mH
        val masterW = master.width
        val masterH = master.height
        val buf = master.pixels
        var peak = 0
        for (my in 0 until mH) {
            val base = bottomUpRowStart(my0 + my, masterW, masterH) + mx0
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
            val base = bottomUpRowStart(my0 + my, masterW, masterH) + mx0
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

    /**
     * Returns a vertically flipped copy of [src], one row per native blit (not per pixel).
     *
     * Only the GPU-halving path uses this, where [src] is the small **cell-resolution** atlas. The snap
     * path deliberately does not: its read-back is the *supersampled* master (ss² times the area), and
     * copying it cost 26–38% of a rasterise until krogue-5uw folded the flip into [bottomUpRowStart].
     */
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
        shiftCache.clear()
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

        // Reference em (px) for the one-off face ink-box ratio measurement (krogue-ux6, [faceInkBoxRatio]).
        // Large enough that capHeight/ascent/descent round cleanly; the ratio is a size-independent face
        // constant, so any generously-sized reference works. Kept at 256 (not shrunk for the small one-time
        // cost) because CI validated the ux6/band-scale GL specs at this value and the integer metric
        // rounding a smaller em introduces would perturb the shipped ink-box ratio unverifiably here.
        const val REF_METRIC_EM = 256

        // TEXT fit holds back this fraction of the cell height at the top AND bottom as breathing margin
        // (krogue-ux6): enough that the tallest accent (Å/Ä) clears the ceiling and the deepest descender
        // (g/j/p/q/y) clears the floor rather than sitting flush against the per-cell scissor edge. Applied
        // by shrinking the rasterised em ([textEmSize]); the placement then centres the ink box in the cell.
        const val TEXT_MARGIN_FRAC = 0.05f

        // CP437 slot of 'x' — the glyph the band-scale downsample measures the x-height/baseline from
        // (Brogue's TEXT_X_HEIGHT is likewise "the height of the 'x' outline"). ASCII 'x' == slot 120.
        const val X_SLOT = 120

        // The cell-filling glyph class (krogue-9x7.4, [isCellFilling]): Unicode's Box Drawing block through
        // the end of Block Elements, i.e. U+2500–U+259F — contiguous, so one range covers both.
        const val BOX_DRAWING_FIRST = 0x2500

        /** Last box-drawing code point; block/shade elements start at U+2580 and are snapped per glyph. */
        const val BOX_DRAWING_LAST = 0x257F

        const val BLOCK_ELEMENTS_LAST = 0x259F

        // U+2588 FULL BLOCK: the glyph that *is* the face's design cell, which [measureDesignCell] measures
        // the cell-filling placement's mapping from.
        const val FULL_BLOCK = '█'

        // Most interior stroke edges an axis may have before the edge-pinning warp (krogue-tg5,
        // [strokeEdges]) gives up and keeps the uniform resample. Box drawing tops out at two strokes per
        // axis — a double line, `╬`/`╔` — i.e. 4 edges; 8 leaves headroom for a face that draws an extra
        // detail while still rejecting a cell-filling *pattern* (the shades `░ ▒ ▓`, dozens of edges), whose
        // periodic dither there is no point snapping.
        const val MAX_STROKE_EDGES = 8

        // Two stroke edges closer than this (in MASTER px) are treated as unsnappable — a stroke thinner
        // than half a master pixel is antialiasing noise, and snapping it would build a degenerate band.
        const val MIN_EDGE_GAP_MASTER = 0.5

        // How much of a rasterised glyph's bitmap box is antialiasing fringe rather than solid ink, per
        // side, in MASTER pixels. FreeType expands a bitmap to whole pixels, so a fractional outline edge
        // partially covers at most its one outermost pixel — hence 1. See [measureDesignCell].
        const val FRINGE_MASTER_PX = 1f

        // How many rasterise geometries [shiftCache] keeps before evicting the least recently used
        // (krogue-9x7.6). Each entry is one int per CP437 slot — 1 KiB — so 64 sizes is ~64 KiB, negligible
        // next to a single page atlas, while still covering a full window drag's worth of distinct cell
        // sizes (a resolution-independent window steps through a few dozen at most, and revisits them).
        const val SHIFT_CACHE_MAX_SIZES = 64

        // TILE fit enlarges the page so a capital fills this fraction of the cell height (uniform em); a
        // small margin below 1 keeps ascenders/tall glyphs off the very edge. Full-em glyphs (block/box)
        // are clamped to fill exactly by the per-glyph fit cap in drawTileGlyph.
        const val TILE_CAP_FILL = 0.82f
    }
}
