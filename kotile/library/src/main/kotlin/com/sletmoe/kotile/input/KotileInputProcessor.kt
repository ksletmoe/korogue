package com.sletmoe.kotile.input

import com.badlogic.gdx.InputProcessor
import com.sletmoe.kotile.rendering.GridLayout

/**
 * A libGDX [InputProcessor] that translates pixel-space mouse events into
 * tile-coordinate events and forwards them to registered [KotileInputListener]s.
 *
 * ## Setup
 *
 * Pass an instance to `Gdx.input.setInputProcessor(processor)` from your
 * `ApplicationAdapter.create()`. Add one or more listeners with [addListener].
 * Remove them with [removeListener] when they are no longer needed.
 *
 * ```kotlin
 * override fun create() {
 *     val processor = KotileInputProcessor(layout = { window.layout })
 *     processor.addListener(object : KotileInputAdapter() {
 *         override fun onTileClicked(tileX: Int, tileY: Int, button: Int) { ... }
 *     })
 *     Gdx.input.setInputProcessor(processor)
 * }
 * ```
 *
 * ## Coordinate system
 *
 * Tile coordinates have a **top-left origin**: tile (0, 0) is the top-left
 * cell, x increases rightward, y increases downward. This matches kotile's
 * rendering convention throughout the library.
 *
 * libGDX Desktop (`InputProcessor` on LWJGL3) delivers mouse pixel positions
 * with a top-left origin as well, so no y-axis flip is performed. Pixels are
 * mapped through the current [GridLayout] via [GridLayout.tileAt], which
 * accounts for the centering offset and the on-screen (possibly scaled,
 * possibly fractional) tile size.
 *
 * ## Free (pixel-space) pointer events
 *
 * Each mouse event *also* fires a content-pixel event — [KotileInputListener.onPointerDown]
 * / [onPointerUp][KotileInputListener.onPointerUp] / [onPointerMoved][KotileInputListener.onPointerMoved]
 * / [onPointerDragged][KotileInputListener.onPointerDragged] — mapped via
 * [GridLayout.contentPixelAt], for free (pixel-space) UI and effects (ADR-0018).
 * These fire alongside the tile events and default to no-ops, so grid/text-only
 * consumers are unaffected. Forward them to a
 * [com.sletmoe.kotile.rendering.UiLayer] for pixel-space widget hit-testing.
 *
 * ## Out-of-bounds policy
 *
 * Mouse events that translate to coordinates outside the tile grid (e.g.
 * clicks in a letterboxed area, or in the partial-tile strip when the window
 * pixel size is not an exact multiple of the tile size) are **silently
 * dropped**: the corresponding [KotileInputListener] method is not called.
 *
 * ## The layout is read lazily
 *
 * The [layout] provider is called on **each event**, so the processor always
 * uses the current placement — important after a resize, which can change the
 * tile size, the visible cell count, and/or the centering offset.
 *
 * ## Key events
 *
 * Key events pass libGDX key codes from [com.badlogic.gdx.Input.Keys] through
 * unmodified. No remapping or action-mapping is performed; that is a
 * consumer-side concern.
 *
 * @param layout returns the current [GridLayout]; called per event. Pass a
 *   provider of the canvas's live layout (e.g. `{ window.layout }`) so mapping
 *   stays correct across resizes.
 */
class KotileInputProcessor(
    private val layout: () -> GridLayout,
) : InputProcessor {
    private val listeners = mutableListOf<KotileInputListener>()

    /**
     * Adds [listener] to the set of receivers. Listeners are notified in
     * insertion order. Adding the same listener instance twice will result in
     * duplicate notifications.
     */
    fun addListener(listener: KotileInputListener) {
        listeners.add(listener)
    }

    /**
     * Removes [listener] from the set of receivers. Has no effect if the
     * listener was not registered.
     */
    fun removeListener(listener: KotileInputListener) {
        listeners.remove(listener)
    }

    // ── libGDX InputProcessor implementation ──────────────────────────────

    /**
     * Translates [screenX]/[screenY] to a tile coordinate and notifies
     * [KotileInputListener.onTileClicked] on each registered listener, or
     * drops the event if the pixel position is outside the tile grid.
     *
     * @param screenX screen pixel X (0 = left, increases right)
     * @param screenY screen pixel Y (0 = top, increases down; libGDX Desktop convention)
     * @param pointer libGDX pointer index (ignored for mouse input)
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    override fun touchDown(
        screenX: Int,
        screenY: Int,
        pointer: Int,
        button: Int,
    ): Boolean {
        val layout = layout()
        layout.contentPixelAt(screenX.toFloat(), screenY.toFloat())?.let { (px, py) ->
            listeners.forEach { it.onPointerDown(px, py, button) }
        }
        val (tx, ty) = layout.tileAt(screenX.toFloat(), screenY.toFloat()) ?: return false
        listeners.forEach { it.onTileClicked(tx, ty, button) }
        return false
    }

    /**
     * Translates [screenX]/[screenY] to a content-pixel position and notifies
     * [KotileInputListener.onPointerUp] on each registered listener (the release
     * half of a free-UI click; there is no tile-coordinate equivalent), or drops
     * the event if the pixel position is outside the grid content rectangle.
     */
    override fun touchUp(
        screenX: Int,
        screenY: Int,
        pointer: Int,
        button: Int,
    ): Boolean {
        layout().contentPixelAt(screenX.toFloat(), screenY.toFloat())?.let { (px, py) ->
            listeners.forEach { it.onPointerUp(px, py, button) }
        }
        return false
    }

    /**
     * Translates [screenX]/[screenY] to a tile coordinate and notifies
     * [KotileInputListener.onTileDragged] on each registered listener, or
     * drops the event if the pixel position is outside the tile grid.
     *
     * @param screenX screen pixel X (0 = left, increases right)
     * @param screenY screen pixel Y (0 = top, increases down; libGDX Desktop convention)
     * @param pointer libGDX pointer index (ignored for mouse input)
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    override fun touchDragged(
        screenX: Int,
        screenY: Int,
        pointer: Int,
    ): Boolean {
        val layout = layout()
        layout.contentPixelAt(screenX.toFloat(), screenY.toFloat())?.let { (px, py) ->
            listeners.forEach { it.onPointerDragged(px, py) }
        }
        val (tx, ty) = layout.tileAt(screenX.toFloat(), screenY.toFloat()) ?: return false
        // libGDX does not pass the button to touchDragged; use -1 as sentinel.
        listeners.forEach { it.onTileDragged(tx, ty, -1) }
        return false
    }

    /**
     * Translates [screenX]/[screenY] to a tile coordinate and notifies
     * [KotileInputListener.onTileHovered] on each registered listener, or
     * drops the event if the pixel position is outside the tile grid.
     *
     * @param screenX screen pixel X (0 = left, increases right)
     * @param screenY screen pixel Y (0 = top, increases down; libGDX Desktop convention)
     */
    override fun mouseMoved(
        screenX: Int,
        screenY: Int,
    ): Boolean {
        val layout = layout()
        layout.contentPixelAt(screenX.toFloat(), screenY.toFloat())?.let { (px, py) ->
            listeners.forEach { it.onPointerMoved(px, py) }
        }
        val (tx, ty) = layout.tileAt(screenX.toFloat(), screenY.toFloat()) ?: return false
        listeners.forEach { it.onTileHovered(tx, ty) }
        return false
    }

    /**
     * Notifies [KotileInputListener.onKeyDown] on each registered listener.
     *
     * @param keycode a libGDX key constant from [com.badlogic.gdx.Input.Keys]
     */
    override fun keyDown(keycode: Int): Boolean {
        listeners.forEach { it.onKeyDown(keycode) }
        return false
    }

    /**
     * Notifies [KotileInputListener.onKeyUp] on each registered listener.
     *
     * @param keycode a libGDX key constant from [com.badlogic.gdx.Input.Keys]
     */
    override fun keyUp(keycode: Int): Boolean {
        listeners.forEach { it.onKeyUp(keycode) }
        return false
    }

    /**
     * Notifies [KotileInputListener.onKeyTyped] on each registered listener.
     *
     * @param character the typed character
     */
    override fun keyTyped(character: Char): Boolean {
        listeners.forEach { it.onKeyTyped(character) }
        return false
    }

    /**
     * Notifies [KotileInputListener.onScrolled] on each registered listener.
     *
     * @param amountX horizontal scroll delta
     * @param amountY vertical scroll delta
     */
    override fun scrolled(
        amountX: Float,
        amountY: Float,
    ): Boolean {
        listeners.forEach { it.onScrolled(amountX, amountY) }
        return false
    }

    /**
     * Part of the [InputProcessor] contract (libGDX 1.14+); not surfaced to
     * [KotileInputListener]. Always returns `false`.
     */
    override fun touchCancelled(
        screenX: Int,
        screenY: Int,
        pointer: Int,
        button: Int,
    ): Boolean = false
}
