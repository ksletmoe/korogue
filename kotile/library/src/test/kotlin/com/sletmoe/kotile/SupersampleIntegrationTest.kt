package com.sletmoe.kotile

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
import com.badlogic.gdx.utils.BufferUtils
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.FractionalScaleMode
import com.sletmoe.kotile.rendering.GammaDownsample
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * GL-gated pixel tests for the tier-2 supersample→downsample path (krogue-1zo).
 *
 * The gamma *math* is locked GL-free by
 * [com.sletmoe.kotile.rendering.GammaColorTest]; here the actual GLSL and the
 * canvas wiring are exercised on real pixels. Skipped unless a (software) GL
 * context is available — on macOS these do not run locally; CI runs them under
 * xvfb + llvmpipe. See [HeadlessGl].
 *
 * Every test disposes its native GL resources in a `finally`: [HeadlessGl] reuses
 * one GL context for the whole JVM, so a leak from a failing assertion would
 * contaminate later tests (the krogue-8lo failure class). The two canvas tests
 * also assert [com.sletmoe.kotile.display.KotileCanvas.supersampledLastPass], so a
 * silent regression to the sharp-bilinear fallback fails them rather than passing
 * on look-alike pixels.
 */
class SupersampleIntegrationTest : FunSpec({

    test("GammaDownsample: a 50/50 black+white footprint resolves to the linear-light midpoint (~0.735), not 0.5")
        .config(enabled = HeadlessGl.available) {
            // Drive the shader directly with a controlled source so the result is deterministic and
            // does not depend on the canvas's scale machinery. Source is two opaque texels — white
            // then black — and the single output pixel is drawn with a footprint of 2 texels, so its
            // four taps land at the two texel centres: two white, two black. A gamma-correct average
            // decodes to linear (0 and 1), averages to 0.5, and re-encodes to ~0.735 (188/255). A
            // naive encoded-space average — the bug this shader exists to avoid — would give 0.5
            // (128/255), so this test genuinely fails without the linear-light conversion.
            val pixels =
                HeadlessGl.render(1, 1, Color.MAGENTA) {
                    val source =
                        Pixmap(2, 1, Pixmap.Format.RGBA8888).apply {
                            blending = Pixmap.Blending.None
                            setColor(Color.WHITE)
                            drawPixel(0, 0)
                            setColor(Color.BLACK) // opaque black (a = 1)
                            drawPixel(1, 0)
                        }
                    val texture =
                        Texture(source).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
                    source.dispose()

                    var shader: ShaderProgram? = null
                    var batch: SpriteBatch? = null
                    try {
                        shader = ShaderProgram(GammaDownsample.VERTEX, GammaDownsample.FRAGMENT)
                        check(shader.isCompiled) { "gamma-downsample shader failed to compile: ${shader.log}" }
                        batch = SpriteBatch()
                        val camera =
                            OrthographicCamera().apply {
                                setToOrtho(false, 1f, 1f)
                                update()
                            }
                        batch.projectionMatrix = camera.combined
                        batch.shader = shader
                        batch.begin()
                        shader.setUniformf("u_footprintTexels", 2f, 1f)
                        shader.setUniformf("u_srcTexel", 1f / 2f, 1f / 1f)
                        batch.draw(TextureRegion(texture), 0f, 0f, 1f, 1f)
                        batch.end()
                    } finally {
                        batch?.dispose()
                        shader?.dispose()
                        texture.dispose()
                    }
                }

            try {
                val out = pixels.averageColor(0, 0, 1, 1)
                // Gamma-correct midpoint, well clear of the naive 0.5 it replaces.
                out.r.toDouble() shouldBe (0.735 plusOrMinus 0.03)
                out.g.toDouble() shouldBe (0.735 plusOrMinus 0.03)
                out.b.toDouble() shouldBe (0.735 plusOrMinus 0.03)
            } finally {
                pixels.dispose()
            }
        }

    test("KotileCanvas superSample: a solid fill at a fractional scale resolves back to the same solid color")
        .config(enabled = HeadlessGl.available) {
            // End-to-end wiring check: a 4x2 fixed grid at native 10x10 (40x20 content) rendered into a
            // 60x30 window is FitScale 1.5x — fractional, so the supersample path activates. A uniform
            // fill must survive the capture→downsample round trip unchanged (uniform in, uniform out,
            // independent of the gamma curve), proving the scene buffer binds, resolves to the content
            // rect, and restores the outer (HeadlessGl capture) framebuffer.
            var usedSupersample = false
            val pixels =
                HeadlessGl.render(60, 30, Color.BLACK) {
                    val window =
                        AsciiTileWindow.create {
                            widthInTiles = 4
                            heightInTiles = 2
                            fitToWindow = false
                            scalePolicy = FitScale
                            fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
                        }
                    try {
                        window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                        window.render()
                        usedSupersample = window.backingCanvas.supersampledLastPass
                    } finally {
                        window.dispose()
                    }
                }

            try {
                // The capture→downsample path must be the one that ran, not the sharp-bilinear fallback.
                usedSupersample shouldBe true
                // Content fills the whole window at 1.5x with no letterbox (both axes scale 1.5). Sample
                // the centre well away from any edge-antialiased border.
                val center = pixels.averageColor(20, 10, 40, 20)
                center.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
                center.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
                center.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
            } finally {
                pixels.dispose()
            }
        }

    test("KotileCanvas superSample: renders a half-white / half-black split with the correct bright and dark halves")
        .config(enabled = HeadlessGl.available) {
            // Proves the supersample path draws real per-cell content (not just a uniform fill) at a
            // fractional scale: the left half of the grid is white, the right half black, and after the
            // 1.5x supersample+downsample the left screen region must be bright and the right dark.
            var usedSupersample = false
            val pixels =
                HeadlessGl.render(60, 30, Color.BLACK) {
                    val window =
                        AsciiTileWindow.create {
                            widthInTiles = 4
                            heightInTiles = 2
                            fitToWindow = false
                            scalePolicy = FitScale
                            fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
                        }
                    try {
                        for (y in 0 until 2) {
                            window.drawTile(0, y, StaticAsciiTile(' ', Color.WHITE, Color.WHITE))
                            window.drawTile(1, y, StaticAsciiTile(' ', Color.WHITE, Color.WHITE))
                            window.drawTile(2, y, StaticAsciiTile(' ', Color.WHITE, Color.BLACK))
                            window.drawTile(3, y, StaticAsciiTile(' ', Color.WHITE, Color.BLACK))
                        }
                        window.render()
                        usedSupersample = window.backingCanvas.supersampledLastPass
                    } finally {
                        window.dispose()
                    }
                }

            try {
                usedSupersample shouldBe true
                // Left quarter (well inside white); right quarter (well inside black).
                val left = pixels.averageColor(5, 10, 20, 20)
                val right = pixels.averageColor(40, 10, 55, 20)
                left.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                right.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            } finally {
                pixels.dispose()
            }
        }

    test("KotileCanvas superSample: resolve restores the caller's bound framebuffer (krogue-s5h)")
        .config(enabled = HeadlessGl.available) {
            // The supersample resolve must draw into whatever framebuffer the caller had bound and
            // leave it bound — the FBO-nesting contract AsciiTileWindow documents (a consumer wrapping
            // render() in their own FrameBuffer for a screenshot/post-process). Binding a hardcoded 0
            // (or the platform default) instead would send the frame to the window and leave the wrong
            // target bound. Render a supersample pass inside a consumer FrameBuffer and assert that
            // buffer is bound again afterwards, and holds the frame.
            var restoredHandle = -1
            var expectedHandle = -1
            var centerBlue = 0.0
            var usedSupersample = false
            HeadlessGl
                .render(60, 30, Color.BLACK) {
                    val outer = FrameBuffer(Pixmap.Format.RGBA8888, 60, 30, false)
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
                    try {
                        window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                        window.render()
                        usedSupersample = window.backingCanvas.supersampledLastPass
                        expectedHandle = outer.framebufferHandle
                        restoredHandle = boundFramebuffer()
                        // Read the consumer buffer (still bound if the contract held) — it must hold the frame.
                        val shot = Pixmap.createFromFrameBuffer(0, 0, 60, 30)
                        try {
                            centerBlue = shot.averageColor(20, 10, 40, 20).b.toDouble()
                        } finally {
                            shot.dispose()
                        }
                    } finally {
                        window.dispose()
                        outer.end()
                        outer.dispose()
                    }
                }.dispose()

            // Without this the test is vacuous: if SUPERSAMPLE silently fell back, render() would draw
            // straight into `outer` (no scene FBO), so nothing rebinds it and the handle/pixel checks
            // pass trivially without ever exercising resolveToScreen's restore.
            usedSupersample shouldBe true
            // A consumer FBO is non-zero on desktop, so this also proves it wasn't reset to 0.
            expectedHandle shouldBe restoredHandle
            centerBlue shouldBe (1.0 plusOrMinus 0.1)
        }
})

/** The framebuffer handle currently bound to `GL_FRAMEBUFFER`. */
private fun boundFramebuffer(): Int {
    val buf = BufferUtils.newIntBuffer(16)
    Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, buf)
    return buf.get(0)
}
