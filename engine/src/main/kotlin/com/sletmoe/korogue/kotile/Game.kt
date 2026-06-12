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
 */
abstract class Game : ApplicationAdapter() {
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

    // -------------------------------------------------------------------------
    // ApplicationAdapter lifecycle
    // -------------------------------------------------------------------------

    override fun create() {
        window = buildWindow()

        val inputProcessor =
            KotileInputProcessor(
                tileWidthPx = { window.tileWidthPx },
                tileHeightPx = { window.tileHeightPx },
                gridWidth = { window.widthInTiles },
                gridHeight = { window.heightInTiles },
            )
        inputProcessor.addListener(
            object : KotileInputAdapter() {
                override fun onKeyDown(keycode: Int) {
                    this@Game.onKeyDown(keycode)
                }
            },
        )
        Gdx.input.inputProcessor = inputProcessor
    }

    override fun render() {
        val deltaMs = (Gdx.graphics.deltaTime * 1000).toLong()
        elapsedMs += deltaMs

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        onTick(deltaMs)
        drawFrame(elapsedMs)
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
