package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.FrameBuffer

/**
 * Boots a real (but hidden) LWJGL3 OpenGL context so rendering code can be
 * exercised and the resulting pixels inspected. Requires a display and
 * (in CI) software OpenGL; [available] reports whether that is the case.
 */
object HeadlessGl {
    val available: Boolean = System.getenv("DISPLAY")?.isNotEmpty() == true

    /**
     * Renders [draw] into a [width]x[height] offscreen buffer cleared to
     * [clear], and returns the pixels with a top-left origin (y increasing
     * downwards). The window size matches the buffer so KotileCanvas's
     * pixel math lands inside it.
     */
    fun render(width: Int, height: Int, clear: Color, draw: () -> Unit): Pixmap {
        var result: Pixmap? = null
        var failure: Throwable? = null

        val listener = object : ApplicationAdapter() {
            override fun create() {
                try {
                    val fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
                    fbo.begin()
                    Gdx.gl.glClearColor(clear.r, clear.g, clear.b, clear.a)
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    draw()
                    val raw = Pixmap.createFromFrameBuffer(0, 0, width, height)
                    fbo.end()
                    result = flipY(raw)
                    raw.dispose()
                    fbo.dispose()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    Gdx.app.exit()
                }
            }
        }

        val config = Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(width, height)
            setInitialVisible(false)
            disableAudio(true)
        }
        Lwjgl3Application(listener, config)

        failure?.let { throw it }
        return result ?: error("headless render produced no pixels")
    }

    private fun flipY(src: Pixmap): Pixmap {
        val dst = Pixmap(src.width, src.height, Pixmap.Format.RGBA8888)
        dst.blending = Pixmap.Blending.None
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                dst.drawPixel(x, y, src.getPixel(x, src.height - 1 - y))
            }
        }
        return dst
    }
}

/** Average color over the half-open pixel rectangle [x0,x1) x [y0,y1). */
fun Pixmap.averageColor(x0: Int, y0: Int, x1: Int, y1: Int): Color {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val p = getPixel(x, y)
            r += (p ushr 24) and 0xff
            g += (p ushr 16) and 0xff
            b += (p ushr 8) and 0xff
            n++
        }
    }
    return Color(r.toFloat() / n / 255f, g.toFloat() / n / 255f, b.toFloat() / n / 255f, 1f)
}
