package com.sletmoe.korogue.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.input.KotileInputAdapter
import com.sletmoe.kotile.input.KotileInputProcessor

/**
 * Base class for korogue games rendered via kotile's [AsciiTileWindow].
 *
 * Extends libGDX [ApplicationAdapter] so the libGDX LWJGL3 backend owns the main loop.
 * Each frame libGDX calls [render]; this drives: input polling → [onTick] → draw.
 *
 * Subclasses must implement:
 * - [buildWindow] — construct and return the [AsciiTileWindow] for this game.
 * - [onTick] — advance game state one frame (called once per [render] invocation).
 * - [drawFrame] — paint the current state into the [window] and call [AsciiTileWindow.render].
 * - [onKeyDown] — react to key presses (libGDX [Input.Keys] codes).
 *
 * Lifecycle:
 * 1. libGDX calls [create] once: [buildWindow] is called here so GPU resources are
 *    allocated after the GL context is ready.
 * 2. libGDX calls [render] every frame: input → [onTick] → [drawFrame].
 * 3. libGDX calls [resize] on window resize: forwarded to [window].
 * 4. libGDX calls [dispose] on exit: [window] is disposed.
 *
 * @param continuousRendering `true` (default, matches libGDX's own default) renders every frame at
 *   vsync regardless of whether anything changed — required for a [com.sletmoe.korogue.loop.RealTimeLoop]
 *   game, since the world advances every frame either way. `false` (krogue-4ul) renders only on
 *   demand: a frame is drawn when [requestRedraw] is called (already wired to every key press) or
 *   while [needsContinuousRedraw] reports `true` (a presentation animation still mid-flight) — for a
 *   turn-based game sitting idle between moves this is the difference between pegging a CPU core at
 *   vsync and costing ~0%. Complements krogue-drk (dirty-region redraw, which is about *what* gets
 *   redrawn rather than *how often*).
 */
abstract class Game(
    private val continuousRendering: Boolean = true,
) : ApplicationAdapter() {
    /** The ASCII tile window created by [buildWindow] during [create]. */
    protected lateinit var window: AsciiTileWindow
        private set

    private var elapsedMs: Long = 0L

    // -------------------------------------------------------------------------
    // Subclass contract
    // -------------------------------------------------------------------------

    /**
     * Called from [create] after the GL context is ready. Construct and return
     * the [AsciiTileWindow] for this game session.
     */
    protected abstract fun buildWindow(): AsciiTileWindow

    /**
     * Called once per frame before [drawFrame]. Advance game state here
     * (creature AI, world ticks, etc.).
     *
     * @param deltaMs wall-clock milliseconds since the previous frame; thread into a
     *   fixed-timestep game loop so world speed is independent of render FPS.
     */
    protected open fun onTick(deltaMs: Long) {}

    /**
     * Called once per frame after [onTick]. Write tiles into [window] and call
     * [AsciiTileWindow.render] (or the viewport overload) to produce the frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds;
     *   pass to [AsciiTileWindow.render] for animated-tile support.
     */
    protected abstract fun drawFrame(elapsedMs: Long)

    /**
     * Called for each key-press event. [keycode] is a libGDX [Input.Keys] constant.
     */
    protected open fun onKeyDown(keycode: Int) {}

    /**
     * Whether the *next* frame is needed regardless of input — e.g. [drawFrame] is mid-way through
     * a presentation animation. Checked once per [render] call; while `true`, [render] keeps calling
     * [requestRedraw] on its own, so a subclass only has to report its current state honestly, not
     * manage the redraw loop itself. Only matters when [continuousRendering] is `false`; the default
     * `false` return is harmless either way (continuous rendering doesn't need requests at all).
     */
    protected open fun needsContinuousRedraw(): Boolean = false

    // -------------------------------------------------------------------------
    // ApplicationAdapter lifecycle
    // -------------------------------------------------------------------------

    override fun create() {
        if (!continuousRendering) Gdx.graphics.setContinuousRendering(false)
        window = buildWindow()

        val inputProcessor =
            // Layout-based mapping so mouse→tile stays correct under the
            // centering/scaling introduced for dynamic resize (krogue-n64).
            KotileInputProcessor(layout = { window.layout })
        inputProcessor.addListener(
            object : KotileInputAdapter() {
                override fun onKeyDown(keycode: Int) {
                    // Every key press is a potential game-state change; request the frame that
                    // shows it (krogue-4ul) -- a no-op when continuousRendering is on, since every
                    // frame renders anyway.
                    requestRedraw()
                    this@Game.onKeyDown(keycode)
                }
            },
        )
        Gdx.input.inputProcessor = inputProcessor
        // Guarantee the first frame paints even with continuous rendering off, rather than relying
        // on the backend's own initial-frame behavior.
        requestRedraw()
    }

    override fun render() {
        val deltaMs = (Gdx.graphics.deltaTime * 1000).toLong()
        elapsedMs += deltaMs

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        onTick(deltaMs)
        drawFrame(elapsedMs)

        if (!continuousRendering && needsContinuousRedraw()) requestRedraw()
    }

    /**
     * Requests that another frame be drawn soon (krogue-4ul) — the hook a subclass calls whenever
     * something outside libGDX's own input dispatch changes what's on screen (network input, a timer
     * firing, anything not already covered by the automatic per-keypress call). A no-op in effect
     * when [continuousRendering] is `true`, since every frame already renders regardless.
     */
    protected fun requestRedraw() {
        Gdx.graphics.requestRendering()
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        if (::window.isInitialized) {
            window.resize(width, height)
        }
    }

    override fun dispose() {
        if (::window.isInitialized) {
            window.dispose()
        }
    }
}
