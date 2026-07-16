package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Boots a real (but hidden) LWJGL3 OpenGL context so rendering code can be
 * exercised and the resulting pixels inspected. Requires a display and
 * (in CI) software OpenGL; [available] reports whether that is the case.
 *
 * ## One application per JVM (krogue-8lo)
 *
 * A single [Lwjgl3Application] is booted lazily on the first [render] and reused
 * for every render afterwards. It used to be one application — a fresh GLFW
 * window and GL context — *per render*, roughly 60 per test JVM, and that made
 * the whole GL suite flaky: on a loaded CI runner (xvfb + llvmpipe) context
 * creation would eventually fail, and because nothing recovers once GLFW is torn
 * down mid-JVM, **every** GL test from that point on failed, across all specs.
 * The boundary moved run to run, so a green build was luck. Proof it was the
 * harness and not any change under test: re-running one commit's build flipped it
 * from red to green, and re-running the commit before it flipped green to red.
 *
 * Reusing one application removes ~60 GLFW/GL context creations. The trade-off is
 * that renders now share a GL context, so resources a test leaks (a renderer it
 * never disposes, say) outlive that test rather than dying with its application.
 * That is a fair trade — leaks are bounded and small, whereas the churn was
 * breaking CI outright — but it does mean tests should dispose what they create.
 *
 * The application runs on its own daemon thread and work is posted to it, since
 * `Lwjgl3Application`'s constructor blocks in the render loop until the app
 * exits. That is fine on Linux (where these tests actually run) but not on macOS,
 * where GLFW must own the first thread — there [available] is false and the GL
 * tests are skipped anyway. To drive GL locally on macOS, use the
 * `JavaExec` + `-XstartOnFirstThread` harness pattern in `kotile/demo`.
 */
object HeadlessGl {
    val available: Boolean = System.getenv("DISPLAY")?.isNotEmpty() == true

    /** How long to wait for the GL thread before declaring it wedged, rather than hanging CI. */
    private const val TIMEOUT_SECONDS = 30L

    /** How long a window resize gets to show up in `Gdx.graphics` before we call it broken. */
    private const val RESIZE_TIMEOUT_SECONDS = 10L

    /** The size the shared window is currently at; renders only resize when they must. */
    private var currentWidthPx = 0
    private var currentHeightPx = 0

    @Volatile
    private var booted = false

    /**
     * A boot failure is permanent and is remembered: there is one application per
     * JVM, so if it could not start, it will not start for the next test either.
     * Without this every remaining GL test would re-attempt and burn the full
     * timeout, turning one failure into an hour of CI.
     */
    private var bootError: Throwable? = null

    /**
     * Renders [draw] into a [width]x[height] offscreen buffer cleared to
     * [clear], and returns the pixels with a top-left origin (y increasing
     * downwards). The window is resized to match the buffer so KotileCanvas's
     * pixel math — which reads `Gdx.graphics` — lands inside it.
     *
     * Synchronized because the window size is now shared state: Kotest runs
     * sequentially today, but two concurrent renders would otherwise resize the
     * one window out from under each other.
     */
    @Synchronized
    fun render(width: Int, height: Int, clear: Color, draw: () -> Unit): Pixmap {
        ensureApp()
        resizeWindowTo(width, height)
        return onGlThread {
            val fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
            fbo.begin()
            Gdx.gl.glClearColor(clear.r, clear.g, clear.b, clear.a)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            draw()
            val raw = Pixmap.createFromFrameBuffer(0, 0, width, height)
            fbo.end()
            val flipped = flipY(raw)
            raw.dispose()
            fbo.dispose()
            flipped
        }
    }

    /** Boots the shared application once, blocking until its GL context is live. */
    @Synchronized
    private fun ensureApp() {
        bootError?.let { throw IllegalStateException("the headless GL application failed to boot earlier", it) }
        if (booted) return

        val ready = CountDownLatch(1)
        var bootFailure: Throwable? = null
        val listener = object : ApplicationAdapter() {
            override fun create() = ready.countDown()
        }
        // Start at 1x1 so the first render's resize is unconditional -- there is no
        // size a test could ask for that we would mistake for "already correct".
        val config = Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(1, 1)
            setInitialVisible(false)
            disableAudio(true)
            // The window is never focused (it is hidden), so the idle rate is the one
            // that governs how fast posted work drains. Keep it at the normal 60.
            setIdleFPS(60)
        }
        val thread = Thread({
            try {
                Lwjgl3Application(listener, config)
            } catch (t: Throwable) {
                bootFailure = t
            } finally {
                ready.countDown()
            }
        }, "kotile-headless-gl")
        thread.isDaemon = true
        thread.start()

        if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val timeout = IllegalStateException("timed out booting the headless GL application")
            bootError = timeout
            throw timeout
        }
        bootFailure?.let {
            bootError = it
            throw it
        }
        booted = true
        currentWidthPx = 0
        currentHeightPx = 0
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { Gdx.app?.exit() } })
    }

    /**
     * Resizes the shared window, waiting until `Gdx.graphics` actually reports the
     * new size. GLFW's resize is not guaranteed to be observable on the same frame
     * it is requested, and a canvas built against a stale size would silently
     * render at the wrong scale/offset — so poll across frames rather than assume.
     */
    private fun resizeWindowTo(width: Int, height: Int) {
        if (currentWidthPx == width && currentHeightPx == height) return

        onGlThread { Gdx.graphics.setWindowedMode(width, height) }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESIZE_TIMEOUT_SECONDS)
        var actual = 0 to 0
        while (System.nanoTime() < deadline) {
            // Each hop crosses a frame boundary, so this waits frames rather than spinning.
            actual = onGlThread { Gdx.graphics.width to Gdx.graphics.height }
            if (actual == width to height) {
                currentWidthPx = width
                currentHeightPx = height
                return
            }
        }
        error("headless window never resized to ${width}x$height (Gdx.graphics reports ${actual.first}x${actual.second})")
    }

    /** Runs [block] on the GL thread and returns its result, rethrowing anything it threw. */
    private fun <T> onGlThread(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        Gdx.app.postRunnable {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            error("timed out waiting for the headless GL thread")
        }
        return result!!.getOrThrow()
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
