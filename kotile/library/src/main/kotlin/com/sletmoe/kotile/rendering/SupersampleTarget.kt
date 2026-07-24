package com.sletmoe.kotile.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable

/**
 * The offscreen render target for the **supersample→downsample** crispness tier
 * (ADR-0036 tier 2 / krogue-1zo). The owning [com.sletmoe.kotile.display.KotileCanvas]
 * captures a whole `begin`/`end` pass — glyphs *and* sprite tiles — into this
 * buffer at a **large integer** tile size (so the grid is pixel-crisp there), then
 * this class resolves it to the window at the exact fractional on-screen size,
 * box-averaging each destination pixel's footprint in **linear light** via the
 * [GammaDownsample] shader (see [GammaColor] for why encoded-space averaging comes
 * out too dark).
 *
 * ## Relationship to [GridCompositeCache]
 *
 * Both wrap an offscreen [FrameBuffer], but at different layers and for different
 * reasons, and they compose. [GridCompositeCache] caches *one grid's* native-1:1
 * composite so an unchanged frame is one blit; it lives inside
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow] /
 * [TileRenderer]. This target sits one level up in the **canvas**, capturing
 * everything those collaborators blit (their cached regions, plus free-layer
 * sprites) into a single supersampled scene and downsampling it once. A pass can
 * use both: each grid resolves its cache to the scene buffer, then the scene
 * buffer resolves to the window.
 *
 * ## GL-state discipline
 *
 * Like [GridCompositeCache] this manages the framebuffer binding with **raw**
 * `glBindFramebuffer`/`glViewport` (not [FrameBuffer.begin]/[FrameBuffer.end]'s
 * stack) so it interleaves safely with the caches' own `preservingFrameBuffer`
 * save/restore: those read `GL_FRAMEBUFFER_BINDING` directly, so as long as the
 * scene buffer is the raw-bound target while they run, their restore puts it back.
 * [reassert] re-binds the scene buffer and its viewport after such an interruption.
 * The framebuffer bound when the pass began is captured (`beginCapture`) and
 * restored on resolve, so the resolved frame lands in the caller's target — the
 * window, or a consumer's own [FrameBuffer] when `render()` is wrapped for a
 * screenshot/post-process (krogue-s5h) — rather than a hardcoded framebuffer 0.
 */
internal class SupersampleTarget(
    private val nativeTileWidthPx: Int,
    private val nativeTileHeightPx: Int,
) : Disposable {
    private var fbo: FrameBuffer? = null
    private var batch: SpriteBatch? = null
    private var flippedRegion: TextureRegion? = null
    private val camera = OrthographicCamera()

    private var widthPx = 0
    private var heightPx = 0

    // The framebuffer that was bound when the current capture pass began -- the window's default FBO
    // normally, but a consumer's own FrameBuffer when render() is wrapped for a screenshot/post-process
    // (the krogue-s5h contract AsciiTileWindow documents). resolveToScreen restores *this*, not a
    // hardcoded 0 nor the platform default, so the resolved frame lands where the caller expects and
    // the caller's binding is left as it was found. Captured in beginCapture before any FBO op.
    private var outerFramebufferHandle = 0

    // Scratch for glGetIntegerv(GL_FRAMEBUFFER_BINDING); reused rather than allocated per frame. Same
    // habit as GridCompositeCache.
    private val glQueryBuffer = BufferUtils.newIntBuffer(16)

    // Permanent-failure flag for the buffer/batch allocation, mirroring shaderFailed: a size the GPU
    // cannot satisfy would otherwise be retried every frame forever. Once set, beginCapture returns
    // false and the canvas falls back to the lighter path.
    private var allocationFailed = false

    // Compiled lazily on first capture; a failure is permanent (fall back to the lighter
    // sharp-bilinear / nearest path) and never retried -- mirrors KotileCanvas's sharp shader.
    private var shader: ShaderProgram? = null
    private var shaderFailed = false

    /** Projection for drawing the scene into the buffer: a y-up ortho of the buffer size. */
    val projectionMatrix: Matrix4 get() = camera.combined

    /**
     * Ensures GPU resources for a [contentWidthPx] x [contentHeightPx] scene, binds the buffer as the
     * active render target with a matching GL viewport, and clears it to transparent. Returns `false`
     * if the shader could not compile or the buffer could not be allocated, in which case nothing was
     * bound and the caller must fall back. On `true`, the caller draws the pass with
     * [projectionMatrix] and finishes with [resolveToScreen].
     */
    fun beginCapture(
        contentWidthPx: Int,
        contentHeightPx: Int,
    ): Boolean {
        if (!ensureShader()) return false
        // Capture the caller's bound framebuffer *before* ensure() may allocate a FrameBuffer (whose
        // constructor leaves framebuffer 0 bound), so resolveToScreen can restore the real outer target.
        outerFramebufferHandle = currentFramebufferHandle()
        if (!ensure(contentWidthPx, contentHeightPx)) return false
        val target = fbo ?: return false
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, target.framebufferHandle)
        Gdx.gl.glViewport(0, 0, widthPx, heightPx)
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        return true
    }

    /**
     * Re-binds the scene buffer and its viewport after another render target (e.g. a
     * [GridCompositeCache]'s own FBO pass) briefly took over mid-capture. Idempotent; safe to call
     * even when nothing clobbered the binding.
     */
    fun reassert() {
        val target = fbo ?: return
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, target.framebufferHandle)
        Gdx.gl.glViewport(0, 0, widthPx, heightPx)
    }

    /**
     * Unbinds the scene buffer (restoring the framebuffer bound when the pass began — see
     * [outerFramebufferHandle]), then draws the captured scene to the on-screen content rectangle at
     * [contentWidthPx] x [contentHeightPx], gamma-correctly downsampled. [projection] is the canvas's
     * real (on-screen) camera projection and the GL viewport
     * must already be the on-screen content rectangle — the caller re-applies its
     * [com.sletmoe.kotile.rendering.GridViewport] before calling this. [footprintTexelsX] /
     * [footprintTexelsY] are the source texels covered by one destination pixel per axis (the scene
     * tile size over the on-screen tile size), which set the downsample tap spacing.
     */
    fun resolveToScreen(
        projection: Matrix4,
        contentWidthPx: Float,
        contentHeightPx: Float,
        footprintTexelsX: Float,
        footprintTexelsY: Float,
    ) {
        val target = fbo ?: return
        val program = shader ?: return
        val b = batch ?: return

        // Restore the framebuffer that was bound when the pass began (see outerFramebufferHandle) and
        // resolve into it -- the window's default FBO normally, or a consumer's own FrameBuffer when
        // render() is wrapped (krogue-s5h). Hardcoding 0 would break both non-zero-default platforms
        // (iOS) and that wrapping contract; the rest of kotile restores the saved handle too.
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, outerFramebufferHandle)

        // FrameBuffer colour textures are stored bottom-up; the region is V-flipped so the scene is
        // upright on screen (same correction GridCompositeCache.cachedRegion applies). Built once per
        // buffer allocation and cached -- the colour texture handle only changes when ensure() rebuilds
        // the FBO, which nulls flippedRegion (disposeGpuResources) so it is lazily recreated here.
        val region =
            flippedRegion ?: TextureRegion(target.colorBufferTexture)
                .apply {
                    // The shader interpolates within each tap, so the scene texture must sample Linear.
                    target.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                    flip(false, true)
                }.also { flippedRegion = it }

        b.projectionMatrix = projection
        b.shader = program
        b.begin()
        program.setUniformf("u_footprintTexels", footprintTexelsX, footprintTexelsY)
        program.setUniformf("u_srcTexel", 1f / widthPx, 1f / heightPx)
        b.color = Color.WHITE
        // Fill the content world (y-up ortho, origin bottom-left): the whole rect, so pxY/glY are 0.
        b.draw(region, 0f, 0f, contentWidthPx, contentHeightPx)
        b.end()
        b.shader = null
    }

    /** The framebuffer handle currently bound to `GL_FRAMEBUFFER` (0 = default/window unless the platform differs). */
    private fun currentFramebufferHandle(): Int {
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, glQueryBuffer)
        return glQueryBuffer.get(0)
    }

    private fun ensureShader(): Boolean {
        if (shaderFailed) return false
        if (shader != null) return true
        val program = ShaderProgram(GammaDownsample.VERTEX, GammaDownsample.FRAGMENT)
        if (!program.isCompiled) {
            Gdx.app?.error("SupersampleTarget", "gamma-downsample shader failed to compile: ${program.log}")
            program.dispose()
            shaderFailed = true
            return false
        }
        shader = program
        return true
    }

    private fun ensure(
        contentWidthPx: Int,
        contentHeightPx: Int,
    ): Boolean {
        if (allocationFailed) return false
        val w = contentWidthPx.coerceAtLeast(1)
        val h = contentHeightPx.coerceAtLeast(1)
        if (w == widthPx && h == heightPx && fbo != null) return true
        disposeGpuResources()

        // Build into locals and only publish on full success, so a failure part-way (e.g. the batch
        // throws after the FBO allocates) can't leave fbo non-null with widthPx == 0. Log + latch the
        // failure like ensureShader rather than silently retrying every frame.
        val newFbo =
            try {
                FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
            } catch (t: Throwable) {
                Gdx.app?.error("SupersampleTarget", "failed to allocate ${w}x$h scene framebuffer", t)
                allocationFailed = true
                return false
            }
        val newBatch =
            try {
                SpriteBatch()
            } catch (t: Throwable) {
                newFbo.dispose()
                Gdx.app?.error("SupersampleTarget", "failed to allocate scene batch", t)
                allocationFailed = true
                return false
            }

        fbo = newFbo
        batch = newBatch
        widthPx = w
        heightPx = h
        camera.setToOrtho(false, w.toFloat(), h.toFloat())
        camera.update()
        return true
    }

    private fun disposeGpuResources() {
        fbo?.dispose()
        batch?.dispose()
        fbo = null
        batch = null
        flippedRegion = null
        widthPx = 0
        heightPx = 0
    }

    override fun dispose() {
        disposeGpuResources()
        shader?.dispose()
        shader = null
    }
}
