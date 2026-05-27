package com.sletmoe.kotile.input

import com.badlogic.gdx.InputProcessor

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
 *     val processor = KotileInputProcessor(
 *         tileWidthPx  = { window.canvas.tileWidthPx },
 *         tileHeightPx = { window.canvas.tileHeightPx },
 *         gridWidth    = { window.widthInTiles },
 *         gridHeight   = { window.heightInTiles },
 *     )
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
 * with a top-left origin as well, so no y-axis flip is performed. The
 * conversion is simply `tileX = pixelX / tileWidthPx`, and the same for y.
 * See [pixelToTile] for the full specification including out-of-bounds handling.
 *
 * ## Out-of-bounds policy
 *
 * Mouse events that translate to coordinates outside the tile grid (e.g.
 * clicks in a letterboxed area, or in the partial-tile strip when the window
 * pixel size is not an exact multiple of the tile size) are **silently
 * dropped**: the corresponding [KotileInputListener] method is not called.
 *
 * ## Tile dimensions are read lazily
 *
 * The [tileWidthPx], [tileHeightPx], [gridWidth], and [gridHeight] lambdas are
 * called on each event so the processor always uses the **current** tile size.
 * This is important after a resize when `fitToWindow = true` changes the tile
 * dimensions.
 *
 * ## Key events
 *
 * Key events pass libGDX key codes from [com.badlogic.gdx.Input.Keys] through
 * unmodified. No remapping or action-mapping is performed; that is a
 * consumer-side concern.
 *
 * @param tileWidthPx returns the current tile width in pixels; called per event
 * @param tileHeightPx returns the current tile height in pixels; called per event
 * @param gridWidth returns the current grid width in tiles; called per event
 * @param gridHeight returns the current grid height in tiles; called per event
 */
class KotileInputProcessor(
    private val tileWidthPx: () -> Int,
    private val tileHeightPx: () -> Int,
    private val gridWidth: () -> Int,
    private val gridHeight: () -> Int,
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
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val (tx, ty) = translate(screenX, screenY) ?: return false
        listeners.forEach { it.onTileClicked(tx, ty, button) }
        return false
    }

    /**
     * Part of the [InputProcessor] contract; tile-click semantics are
     * delivered by [touchDown]. Always returns `false`.
     */
    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = false

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
    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        val (tx, ty) = translate(screenX, screenY) ?: return false
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
    override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
        val (tx, ty) = translate(screenX, screenY) ?: return false
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
    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        listeners.forEach { it.onScrolled(amountX, amountY) }
        return false
    }

    /**
     * Part of the [InputProcessor] contract (libGDX 1.14+); not surfaced to
     * [KotileInputListener]. Always returns `false`.
     */
    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = false

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun translate(screenX: Int, screenY: Int): Pair<Int, Int>? =
        pixelToTile(
            screenPixelX = screenX,
            screenPixelY = screenY,
            tileWidthPx = tileWidthPx(),
            tileHeightPx = tileHeightPx(),
            gridWidthInTiles = gridWidth(),
            gridHeightInTiles = gridHeight(),
        )
}
