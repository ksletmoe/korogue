package com.sletmoe.kotile.display

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.rendering.FractionalScaleMode
import com.sletmoe.kotile.rendering.GridLayout
import com.sletmoe.kotile.rendering.GridViewport
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.SharpBilinear
import com.sletmoe.kotile.rendering.SupersampleTarget
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws tile-sized texture regions onto the screen via a batched [SpriteBatch].
 *
 * ## Coordinate system
 *
 * **Tile coordinates** use a **top-left origin**: tile (0, 0) is the top-left
 * cell of the canvas, x increases rightward, and y increases downward.
 *
 * Internally, tile (x, y) is mapped to GL screen coordinates (bottom-left
 * origin, y up) before drawing. This mapping is an implementation detail and
 * is not visible to callers of [drawTile].
 *
 * **Mouse pixel coordinates** from `Gdx.input` (via libGDX Desktop /
 * `InputProcessor`) also use a top-left origin (0, 0 = top-left of window, y
 * increases downward). They are therefore in the same axis orientation as tile
 * coordinates and do **not** need a y-axis flip before pixel-to-tile mapping.
 * See [layout] and [com.sletmoe.kotile.rendering.GridLayout.tileAt] for the
 * offset/scale-aware conversion.
 *
 * Draw calls must be made between [begin] and [end]. Instances own GPU
 * resources and must be [dispose]d.
 *
 * ## Display modes and scaling
 *
 * The canvas places the tile grid within the window using a [GridLayout],
 * recomputed on every [resize]. Two modes are supported:
 *
 * - **Reflow** (default): tiles keep their native pixel size ([tileWidthPx] x
 *   [tileHeightPx]); the visible tile count grows and shrinks with the window,
 *   and any sub-tile remainder is split into centered letterbox margins.
 * - **Fixed grid** (via [useFixedGrid]): the tile count is fixed; tiles are
 *   scaled by a [ScalePolicy] to fill the window while preserving aspect ratio,
 *   then centered with letterbox margins.
 *
 * ### Fractional-scale filtering
 *
 * When a fixed grid is scaled by a **non-integer** factor (the
 * [com.sletmoe.kotile.rendering.FitScale] path), nearest-neighbour sampling
 * makes glyph strokes shimmer and change width. The canvas detects a fractional
 * scale and transparently switches the batch to a **sharp-bilinear** shader
 * ([SharpBilinear]) with `Linear` texture sampling for that frame, then restores
 * `Nearest` afterwards. Because every [drawTile] — glyph *and* sprite tile —
 * flows through this one batch, the smoothing covers both layers. Integer and
 * reflow scales are unaffected (default shader + nearest, pixel-perfect).
 *
 * In both modes the grid is centered; leftover window space is left at the
 * clear color. Placement and the GL projection are driven by a [GridViewport],
 * which sets the GL viewport to the content rectangle via `HdpiUtils.glViewport`:
 * this reconciles logical points with backbuffer pixels on HiDPI/retina displays
 * and **hard-clips** overflow (a fixed grid larger than the window at 1x) and
 * partial-tile bleed, so the letterbox bars are guaranteed to stay at the clear
 * color.
 *
 * ### Fractional-scale mode (tier 2, opt-in)
 *
 * With [fractionalScaleMode] = [FractionalScaleMode.SUPERSAMPLE], a **fractional**
 * scale is handled differently: instead of the lighter sharp-bilinear shader, the
 * whole pass — glyphs *and* sprite tiles — is captured into an offscreen
 * [SupersampleTarget] at a large integer tile size (pixel-crisp) and then resolved
 * to the window with a gamma-correct downsample (ADR-0036 tier 2, krogue-1zo).
 * Integer scales and reflow are unaffected (still direct + nearest, pixel-perfect),
 * and if the downsample shader or buffer is unavailable the canvas falls back to
 * sharp-bilinear. This is the heavier, higher-fidelity fractional path;
 * [FractionalScaleMode.SHARP_BILINEAR] remains the default.
 *
 * The class is `open` to allow subclassing — for example, in tests that need
 * to track dispose calls, or in consumers that want to add instrumentation.
 *
 * @property tileWidthPx a tile's **native** width in pixels (pre-scaling)
 * @property tileHeightPx a tile's **native** height in pixels (pre-scaling)
 * @param fractionalScaleMode how a fractional fixed-grid scale is smoothed (see
 *   the class doc and [FractionalScaleMode]); defaults to
 *   [FractionalScaleMode.SHARP_BILINEAR].
 */
open class KotileCanvas(
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    private val fractionalScaleMode: FractionalScaleMode = FractionalScaleMode.SHARP_BILINEAR,
) : Disposable {
    private val batch = SpriteBatch()
    private val viewport = GridViewport(tileWidthPx, tileHeightPx)

    // Allocated lazily on the first fractional-scale pass when the mode is SUPERSAMPLE; null otherwise so a
    // canvas that never opts in (or never hits a fractional scale) pays nothing. Owns a scene FBO and
    // the gamma-downsample shader/batch. See SupersampleTarget.
    private var supersampleTarget: SupersampleTarget? = null

    // Non-null only for the duration of a supersample capture pass (between begin and end): the
    // integer-scaled layout the scene is drawn against, which [layout] returns during the pass so the
    // grid collaborators (AsciiTileWindow/TileRenderer) blit at the supersample size without knowing
    // this mode exists. Cleared in end(). See computeSupersampleLayout.
    private var supersampleLayout: GridLayout? = null

    // Source texels per on-screen pixel on each axis during the active supersample pass; drives the
    // downsample tap spacing in resolveToScreen. Set in begin(), consumed in end().
    private var ssFootprintX = 1f
    private var ssFootprintY = 1f

    // Sharp-bilinear filtering for fractional (FitScale) scaling. The shader is
    // compiled lazily the first time a fractional scale is drawn; if compilation
    // fails we fall back to the default batch shader (nearest) and never retry.
    // See SharpBilinear and krogue-m2x.
    private var sharpShader: ShaderProgram? = null
    private var sharpShaderFailed = false

    /** Whether the current [begin]/[end] pass is drawing with sharp-bilinear on. */
    private var sharpActive = false

    /** Whether the current [begin]/[end] pass is capturing into the supersample scene buffer. */
    private var ssActive = false

    /**
     * Whether the most recent [begin] actually engaged the supersample capture path (mode is
     * SUPERSAMPLE, scale is fractional, and the target prepared). Exposed (module-internal) purely for
     * test observability: a pixel test asserting "the scene looks right" can't tell a working
     * supersample pass from the sharp-bilinear fallback silently taking over, so the GL specs read this
     * to prove the path they mean to exercise is the one that ran (mirrors the [disposed] seam).
     */
    internal var supersampledLastPass: Boolean = false
        private set

    /** The texture whose size/filter the sharp shader was last configured for, this pass. */
    private var lastSharpTexture: Texture? = null

    /** Textures switched to a Linear mag filter this pass, restored to Nearest in [end]. */
    private val linearizedTextures = LinkedHashSet<Texture>()

    /**
     * Current placement of the grid within the window: visible column/row
     * count, on-screen (possibly scaled) tile size, and the centering offset.
     * Recomputed on every [resize] and mode change. Use [GridLayout.tileAt] to
     * map a mouse pixel position to a tile cell under the current layout.
     *
     * During an active supersample capture pass (between [begin] and [end] at a
     * fractional scale) this returns the **supersample** layout the scene is drawn
     * against — larger, integer-scaled tiles with no letterbox offset — so
     * everything drawn in the pass lands in the offscreen scene at that size and is
     * downsampled uniformly. Input hit-testing reads [layout] outside a render pass,
     * where it is the real on-screen placement. Callers that must have the on-screen
     * placement regardless (e.g. mid-pass) can read it off [resize] state; in
     * practice only draw code runs mid-pass, and draw code *wants* the pass layout.
     */
    val layout: GridLayout get() = supersampleLayout ?: viewport.layout

    /** Current drawable width in pixels (the application's framebuffer width). */
    val widthPx: Int get() = Gdx.graphics.width

    /** Current drawable height in pixels (the application's framebuffer height). */
    val heightPx: Int get() = Gdx.graphics.height

    /** Number of tile columns currently displayed (see [layout]). */
    val width: Int get() = layout.columns

    /** Number of tile rows currently displayed (see [layout]). */
    val height: Int get() = layout.rows

    init {
        resize(widthPx, heightPx)
    }

    /**
     * Switches to **fixed-grid** mode: [columns] x [rows] cells scaled by
     * [policy] to fill the window (preserving aspect ratio) and centered with
     * letterbox margins. Recomputes the [layout] immediately.
     *
     * A canvas has exactly **one** [layout]. If several windows share this
     * canvas (see [com.sletmoe.kotile.display.ascii.AsciiTileWindow.createWithCanvas]),
     * at most one of them may drive a fixed grid — the last call wins, so
     * driving fixed grids of *different* dimensions from two panes makes them
     * fight (both then render/hit-test with whichever dimensions were set last).
     */
    fun useFixedGrid(
        columns: Int,
        rows: Int,
        policy: ScalePolicy = IntegerScale,
    ) {
        viewport.useFixedGrid(columns, rows, policy)
        recomputeLayout()
    }

    /**
     * Switches to **reflow** mode (the default): native-size tiles, visible
     * count derived from the window, sub-tile remainder centered. Recomputes
     * the [layout] immediately.
     */
    fun useReflow() {
        viewport.useReflow()
        recomputeLayout()
    }

    /**
     * Updates the projection to a [widthPx] x [heightPx] viewport and recomputes
     * the grid [layout]. Call this from the application's resize callback with the
     * **logical** window size (as libGDX delivers to `ApplicationListener.resize`);
     * the [GridViewport] reconciles it to backbuffer pixels on HiDPI displays.
     */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ) {
        viewport.update(widthPx, heightPx, true)
        batch.projectionMatrix = viewport.camera.combined
    }

    private fun recomputeLayout() {
        viewport.update(widthPx, heightPx, true)
        batch.projectionMatrix = viewport.camera.combined
    }

    /**
     * Begins a batch of [drawTile] calls. Re-applies this canvas's [GridViewport]
     * first so the GL viewport (a global) is set to *this* canvas's content
     * rectangle even when several canvases share the frame.
     *
     * If the current layout scales tiles by a non-integer factor, the batch is
     * switched to the [SharpBilinear] shader for this pass (see the class doc);
     * otherwise the default nearest-neighbour path is used.
     */
    fun begin() {
        ssActive = fractionalScaleMode == FractionalScaleMode.SUPERSAMPLE && isFractionalScale() && beginSupersample()
        supersampledLastPass = ssActive

        if (!ssActive) {
            viewport.apply()
            batch.projectionMatrix = viewport.camera.combined
        }

        // Supersample renders the scene at an integer tile size, so it is crisp with the default
        // nearest path -- sharp-bilinear is the *alternative* fractional strategy, never stacked on
        // top of it.
        sharpActive = !ssActive && isFractionalScale() && ensureSharpShader() != null
        lastSharpTexture = null
        batch.shader = if (sharpActive) sharpShader else null

        batch.begin()

        if (sharpActive) {
            // The shader is bound now (SpriteBatch.begin binds it), so uniforms
            // can be set. u_textureSize is set per-texture in drawTile.
            sharpShader!!.setUniformf("u_scale", scaleX(), scaleY())
        }
    }

    /**
     * Sets up the offscreen supersample scene for this pass: computes the integer-scaled
     * [supersampleLayout], binds the [SupersampleTarget] sized to it, and points the batch projection
     * at the scene. Returns `false` (and leaves nothing bound) if the target could not be prepared, so
     * [begin] falls back to the on-screen path.
     */
    private fun beginSupersample(): Boolean {
        val real = viewport.layout
        val ss = computeSupersampleLayout(real)
        val target = supersampleTarget ?: SupersampleTarget(tileWidthPx, tileHeightPx).also { supersampleTarget = it }
        val ok = target.beginCapture(ss.contentWidthPx.roundToInt(), ss.contentHeightPx.roundToInt())
        if (!ok) return false
        supersampleLayout = ss
        ssFootprintX = ss.tileWidthPx / real.tileWidthPx
        ssFootprintY = ss.tileHeightPx / real.tileHeightPx
        batch.projectionMatrix = target.projectionMatrix
        return true
    }

    /**
     * The supersample scene layout for a given on-screen [real] layout: the same grid, its tiles
     * rounded **up** to the next integer multiple of the native tile size (so the scene is a crisp
     * integer-scaled render), with no letterbox offset (the scene buffer *is* the content rect).
     */
    private fun computeSupersampleLayout(real: GridLayout): GridLayout {
        val ssScaleX = ceil(real.tileWidthPx / tileWidthPx).toInt().coerceAtLeast(1)
        val ssScaleY = ceil(real.tileHeightPx / tileHeightPx).toInt().coerceAtLeast(1)
        return GridLayout(
            columns = real.columns,
            rows = real.rows,
            tileWidthPx = (ssScaleX * tileWidthPx).toFloat(),
            tileHeightPx = (ssScaleY * tileHeightPx).toFloat(),
            offsetXPx = 0f,
            offsetYPx = 0f,
        )
    }

    /**
     * Re-applies this canvas's [GridViewport] (GL viewport + projection matrix) **without**
     * restarting the batch. Required after drawing to a different GL target in the middle of an
     * active [begin]/[end] pass — libGDX's `FrameBuffer.end()` always resets the GL viewport to the
     * full backbuffer, not whatever was active before it, so a canvas whose content rect is
     * letterboxed/offset (or smaller than the backbuffer, e.g. under HiDPI) must restore its own
     * viewport before any further [drawTile]/[drawSprite] calls or they land in the wrong place
     * (krogue-drk, ADR-0024 — [com.sletmoe.kotile.rendering.GridCompositeCache] is the motivating
     * caller). Safe to call even when nothing clobbered the viewport; it just re-applies the same
     * values.
     */
    internal fun reapplyViewport() {
        if (ssActive) {
            // Mid-pass we are rendering into the scene buffer, not the window: re-assert *its* binding
            // and viewport (a GridCompositeCache pass may have swapped targets and restored only the
            // raw binding), and keep the scene projection.
            supersampleTarget?.reassert()
            supersampleLayout?.let { batch.projectionMatrix = supersampleTarget!!.projectionMatrix }
            return
        }
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
    }

    /**
     * Ends the current batch, flushing it to the screen. Restores the
     * `Nearest` mag filter on any textures the sharp-bilinear pass switched to
     * `Linear`, so textures are left in their default crisp state.
     */
    fun end() {
        batch.end()
        if (ssActive) {
            // The pass drew into the scene buffer; resolve it to the window, gamma-downsampled. Apply
            // the real (on-screen) viewport/projection first so the scene lands in the content rect
            // with its letterbox, then hand that projection to the target.
            val real = viewport.layout
            supersampleLayout = null
            ssActive = false
            viewport.apply()
            batch.projectionMatrix = viewport.camera.combined
            supersampleTarget?.resolveToScreen(
                projection = viewport.camera.combined,
                contentWidthPx = real.contentWidthPx,
                contentHeightPx = real.contentHeightPx,
                footprintTexelsX = ssFootprintX,
                footprintTexelsY = ssFootprintY,
            )
        }
        if (linearizedTextures.isNotEmpty()) {
            linearizedTextures.forEach { it.setFilter(it.minFilter, Texture.TextureFilter.Nearest) }
            linearizedTextures.clear()
        }
    }

    /** On-screen pixels per native tile texel on the x axis (the horizontal scale factor). */
    private fun scaleX(): Float = layout.tileWidthPx / tileWidthPx

    /** On-screen pixels per native tile texel on the y axis (the vertical scale factor). */
    private fun scaleY(): Float = layout.tileHeightPx / tileHeightPx

    /** True when either axis is scaled by a non-integer factor (needs smoothing). */
    private fun isFractionalScale(): Boolean = !isNearInteger(scaleX()) || !isNearInteger(scaleY())

    private fun isNearInteger(v: Float): Boolean = abs(v - v.roundToInt()) < 1e-3f

    /**
     * Lazily compiles the sharp-bilinear shader, returning it or `null` if
     * compilation failed (in which case the default shader is used and no retry
     * is attempted). Must be called with a live GL context.
     */
    private fun ensureSharpShader(): ShaderProgram? {
        if (sharpShaderFailed) return null
        sharpShader?.let { return it }
        val program = ShaderProgram(SharpBilinear.VERTEX, SharpBilinear.FRAGMENT)
        if (!program.isCompiled) {
            Gdx.app?.error("KotileCanvas", "sharp-bilinear shader failed to compile: ${program.log}")
            program.dispose()
            sharpShaderFailed = true
            return null
        }
        sharpShader = program
        return program
    }

    /**
     * Draws [region] in the cell at column [x], row [y] (top-left origin),
     * scaled to the current on-screen tile size and offset by the layout's
     * centering margin, then multiplied by [tint] (white = unchanged). Must be
     * called between [begin] and [end].
     *
     * This is grid sugar over [drawSprite]: it maps the cell's top-left corner
     * to content pixels via the current [layout] and draws at the on-screen tile
     * size. Free-layer content (effects, pixel-space UI) uses [drawSprite]
     * directly. See ADR-0018.
     *
     * [flipX]/[flipY] mirror the tile in place (krogue-csc) — see [drawSprite] for how.
     */
    fun drawTile(
        x: Int,
        y: Int,
        region: TextureRegion,
        tint: Color = Color.WHITE,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ) {
        val l = layout
        drawSprite(
            pxX = x * l.tileWidthPx,
            pxY = y * l.tileHeightPx,
            region = region,
            w = l.tileWidthPx,
            h = l.tileHeightPx,
            tint = tint,
            flipX = flipX,
            flipY = flipY,
        )
    }

    /**
     * Draws [region] at content pixel position ([pxX], [pxY]) — the sprite's
     * **top-left** corner (pre-rotation) — sized [w] x [h], multiplied by [tint]
     * (white = unchanged) and rotated by [rotationDeg] about its own center.
     * Must be called between [begin] and [end].
     *
     * This is the real drawing primitive; [drawTile] is grid-snapped sugar over
     * it (ADR-0018). It lets free layers (projectiles, particles, pixel-space
     * UI) place content at sub-tile resolution that interpolates smoothly
     * between cells.
     *
     * ## Coordinate space
     *
     * ([pxX], [pxY]) are **content pixels** in the current [layout]: `(0, 0)` is
     * the top-left of the grid content rectangle, x increases rightward, y
     * increases downward — the same origin as tile and mouse coordinates. The
     * space is the *scaled* on-screen layout, so free content stays locked to
     * the same scale and letterbox as the grid; e.g. the center of cell
     * `(c, r)` is `((c + 0.5f) * layout.tileWidthPx, (r + 0.5f) * layout.tileHeightPx)`.
     * The GL y-flip and centering offset are applied here, not by the caller.
     *
     * ## Rotation
     *
     * [rotationDeg] uses this same top-left-origin/Y-down (screen) convention:
     * `0` is unrotated, and a positive angle turns the sprite **clockwise** as
     * drawn on screen (e.g. `90` points what was "up" in the source art toward
     * the right) — matching `atan2(dy, dx)` on a velocity in this same content-
     * pixel space, so a projectile can face `atan2(velYPerMs, velXPerMs)` in
     * degrees directly. Internally this is negated before reaching libGDX's
     * `SpriteBatch`, whose rotation is counter-clockwise in its Y-up GL world —
     * the same axis flip [pxY] already gets, just for an angle instead of a
     * coordinate. Rotation pivots on the sprite's center (`w/2, h/2`).
     *
     * Fractional-scale smoothing (sharp-bilinear) applies to free sprites too:
     * every draw funnels through the same batch and per-texture shader setup.
     *
     * [blend] controls how the drawn pixels combine with what's already on screen — [BlendMode.NORMAL]
     * (the default) composites over it; [BlendMode.ADDITIVE] adds to it instead, the standard way
     * to brighten a sprite (e.g. a hit-flash) toward white. [tint] alone cannot do this: it
     * multiplies the texture's own pixel values, so a dark pixel stays dark no matter how bright
     * the tint is — GDX's `Color` also clamps every component to `[0, 1]` regardless, so an
     * intentionally over-bright tint isn't even representable. Additive blending genuinely adds
     * light instead of just recoloring, which is the only way a multiply tint can't fake.
     *
     * [flipX]/[flipY] mirror the drawn region horizontally/vertically (e.g. a creature sprite
     * facing the direction it last moved, krogue-csc) without mutating [region] itself: the
     * region's UVs are flipped for this one draw call and restored immediately after, since
     * [region] may be a long-lived instance shared across many draws (e.g. an animation frame
     * reused every tick) rather than a fresh one safe to mutate permanently.
     */
    fun drawSprite(
        pxX: Float,
        pxY: Float,
        region: TextureRegion,
        w: Float,
        h: Float,
        tint: Color = Color.WHITE,
        rotationDeg: Float = 0f,
        blend: BlendMode = BlendMode.NORMAL,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ) {
        if (sharpActive) configureSharpFor(region.texture)
        blend.apply(batch)
        batch.color = tint
        if (flipX || flipY) region.flip(flipX, flipY)
        // Coordinates are content-relative: the GridViewport places the content
        // rectangle within the window (the centering offset is the GL viewport's
        // position, not baked in here). Flip the top-left-origin pxY to the
        // viewport's y-up world: the sprite's bottom edge sits at
        // contentHeightPx - pxY - h.
        val glY = layout.contentHeightPx - pxY - h
        if (rotationDeg == 0f) {
            batch.draw(region, pxX, glY, w, h)
        } else {
            batch.draw(region, pxX, glY, w / 2f, h / 2f, w, h, 1f, 1f, -rotationDeg)
        }
        if (flipX || flipY) region.flip(flipX, flipY) // flip() toggles, so flipping twice restores the original
        BlendMode.NORMAL.apply(batch) // never leak a non-default blend function into the next draw call
    }

    /**
     * Prepares the sharp-bilinear shader for drawing [texture]: when the bound
     * texture changes, flushes prior geometry (so it keeps the previous
     * `u_textureSize`), points the shader at the new texel size, and switches the
     * texture to a `Linear` mag filter (recorded for restoration in [end]).
     */
    private fun configureSharpFor(texture: Texture) {
        if (texture === lastSharpTexture) return
        batch.flush() // commit prior draws under the previous u_textureSize before changing it
        sharpShader!!.setUniformf("u_textureSize", texture.width.toFloat(), texture.height.toFloat())
        if (linearizedTextures.add(texture)) {
            texture.setFilter(texture.minFilter, Texture.TextureFilter.Linear)
        }
        lastSharpTexture = texture
    }

    /**
     * Whether [dispose] has been called on this canvas. Exposed (module-internal)
     * so ownership tests can observe that a window disposed the canvas it owns —
     * and left a shared one alone — rather than only checking that dispose threw
     * nothing.
     */
    internal var disposed: Boolean = false
        private set

    /** Disposes the underlying sprite batch, the sharp-bilinear shader, and the supersample scene target, if any. */
    override fun dispose() {
        batch.dispose()
        sharpShader?.dispose()
        supersampleTarget?.dispose()
        disposed = true
    }
}
