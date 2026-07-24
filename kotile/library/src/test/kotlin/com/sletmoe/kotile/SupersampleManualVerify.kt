package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.FractionalScaleMode
import com.sletmoe.kotile.rendering.GammaDownsample

/**
 * macOS-only manual verification for the tier-2 supersample→downsample path
 * (krogue-1zo). The Kotest GL specs ([SupersampleIntegrationTest]) can't run on
 * macOS (GLFW needs the process's first thread, which Gradle test workers don't
 * own), so this boots a real GL context on the main thread via
 * `-XstartOnFirstThread` and reproduces those specs' **exact geometry and GL
 * state** — same window size, same capture-FBO wrapping (handle != 0, matching
 * [HeadlessGl]), same internal [GammaDownsample] shader — then reads the pixels
 * back and prints PASS/FAIL. It lives in the test sourceset so it can reach the
 * `internal` shader and never ships.
 *
 *   ./gradlew :kotile:library:ssVerify
 */
private const val WIN_W = 60
private const val WIN_H = 30

private class SupersampleManualVerify : ApplicationAdapter() {
    private var frame = 0
    private var failures = 0

    // Backbuffer-to-logical ratio (2 on a retina Mac, 1 on CI's non-retina xvfb). The canvas places
    // content via HdpiUtils at backbuffer scale, so the capture FBO and sample rects must too; on CI
    // this is 1 and the harness reduces exactly to the committed spec's geometry.
    private var hidpi = 1
    private val glQueryBuffer = BufferUtils.newIntBuffer(16)

    override fun render() {
        // Let the initial resize settle so Gdx.graphics reports WIN_WxWIN_H before the pipeline checks.
        if (++frame < 2) return
        hidpi = (Gdx.graphics.backBufferWidth / Gdx.graphics.width).coerceAtLeast(1)
        val bb = "${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}"
        println("SSVERIFY logical=${Gdx.graphics.width}x${Gdx.graphics.height} backbuffer=$bb hidpi=$hidpi")

        gammaShaderCheck()
        solidFillCheck()
        halfSplitCheck()
        fboRestoreCheck()

        println(if (failures == 0) "SSVERIFY: ALL PASSED" else "SSVERIFY: $failures FAILED")
        Gdx.app.exit()
    }

    /** Mirrors the committed krogue-s5h spec: the resolve must restore the caller's bound framebuffer. */
    private fun fboRestoreCheck() {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val outer = FrameBuffer(Pixmap.Format.RGBA8888, WIN_W * hidpi, WIN_H * hidpi, false)
        outer.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val window =
            AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
                scalePolicy = FitScale
                fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
            }
        var bound = -1
        try {
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
            window.render()
            glQueryBuffer.clear()
            Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, glQueryBuffer)
            bound = glQueryBuffer.get(0)
        } finally {
            window.dispose()
            outer.end()
            outer.dispose()
        }
        report(
            "resolve restores the caller's framebuffer (s5h)",
            bound == outer.framebufferHandle && outer.framebufferHandle != 0,
            "bound=$bound expected=${outer.framebufferHandle}",
        )
    }

    /** Mirrors the committed "50/50 black+white footprint → ~0.735" spec, driving the internal shader directly. */
    private fun gammaShaderCheck() {
        val pixels =
            capture(1, 1, Color.MAGENTA) {
                val source =
                    Pixmap(2, 1, Pixmap.Format.RGBA8888).apply {
                        blending = Pixmap.Blending.None
                        setColor(Color.WHITE)
                        drawPixel(0, 0)
                        setColor(Color.BLACK)
                        drawPixel(1, 0)
                    }
                val texture =
                    Texture(
                        source,
                    ).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
                source.dispose()
                val shader = ShaderProgram(GammaDownsample.VERTEX, GammaDownsample.FRAGMENT)
                check(shader.isCompiled) { "shader compile failed: ${shader.log}" }
                val batch = SpriteBatch()
                val camera =
                    OrthographicCamera().apply {
                        setToOrtho(false, 1f, 1f)
                        update()
                    }
                batch.projectionMatrix = camera.combined
                batch.shader = shader
                batch.begin()
                shader.setUniformf("u_footprintTexels", 2f, 1f)
                shader.setUniformf("u_srcTexel", 0.5f, 1f)
                batch.draw(TextureRegion(texture), 0f, 0f, 1f, 1f)
                batch.end()
                batch.dispose()
                shader.dispose()
                texture.dispose()
            }
        val g = pixels.averageColor(0, 0, 1, 1).r
        report("gamma-shader midpoint", g in 0.705f..0.765f, "expected ~0.735, got $g (naive would be ~0.5)")
        pixels.dispose()
    }

    private fun solidFillCheck() {
        val pixels =
            capture(WIN_W * hidpi, WIN_H * hidpi, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 4
                        heightInTiles = 2
                        fitToWindow = false
                        scalePolicy = FitScale
                        fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
                    }
                window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.render()
                window.dispose()
            }
        val c = pixels.averageColor(20 * hidpi, 10 * hidpi, 40 * hidpi, 20 * hidpi)
        report("solid-fill blue survives SS", c.b > 0.85f && c.r < 0.15f, "center=$c")
        pixels.dispose()
    }

    private fun halfSplitCheck() {
        val pixels =
            capture(WIN_W * hidpi, WIN_H * hidpi, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 4
                        heightInTiles = 2
                        fitToWindow = false
                        scalePolicy = FitScale
                        fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
                    }
                for (y in 0 until 2) {
                    window.drawTile(0, y, StaticAsciiTile(' ', Color.WHITE, Color.WHITE))
                    window.drawTile(1, y, StaticAsciiTile(' ', Color.WHITE, Color.WHITE))
                    window.drawTile(2, y, StaticAsciiTile(' ', Color.WHITE, Color.BLACK))
                    window.drawTile(3, y, StaticAsciiTile(' ', Color.WHITE, Color.BLACK))
                }
                window.render()
                window.dispose()
            }
        val left = pixels.averageColor(5 * hidpi, 10 * hidpi, 20 * hidpi, 20 * hidpi).r
        val right = pixels.averageColor(40 * hidpi, 10 * hidpi, 55 * hidpi, 20 * hidpi).r
        report("half-split bright left / dark right", left > 0.85f && right < 0.15f, "left=$left right=$right")
        pixels.dispose()
    }

    private fun report(
        name: String,
        ok: Boolean,
        detail: String,
    ) {
        if (!ok) failures++
        println("  [${if (ok) "PASS" else "FAIL"}] $name — $detail")
    }

    /**
     * Renders [draw] into a [w]x[h] capture FBO exactly as [HeadlessGl.render] does, returning upright
     * pixels. [w]/[h] are FBO pixels: the gamma check uses an exact 1x1 (its draw sets its own
     * viewport, independent of the backbuffer, and needs the single pixel to land at the texel-centre
     * footprint), while the pipeline checks pass backbuffer-sized dims so the canvas's HdpiUtils
     * placement lands fully inside (on CI, hidpi=1 and those reduce to the committed spec's geometry).
     */
    private fun capture(
        w: Int,
        h: Int,
        clear: Color,
        draw: () -> Unit,
    ): Pixmap {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        Gdx.gl.glClearColor(clear.r, clear.g, clear.b, clear.a)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(clear.r, clear.g, clear.b, clear.a)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        draw()
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (y in 0 until h) for (x in 0 until w) flipped.drawPixel(x, y, raw.getPixel(x, h - 1 - y))
        raw.dispose()
        fbo.dispose()
        return flipped
    }
}

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile supersample verify")
            setWindowedMode(WIN_W, WIN_H)
            disableAudio(true)
            setInitialVisible(false)
        }
    Lwjgl3Application(SupersampleManualVerify(), config)
}
