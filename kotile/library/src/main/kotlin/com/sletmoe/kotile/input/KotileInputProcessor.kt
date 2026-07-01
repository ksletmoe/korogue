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
 * ## Scaling and letterboxing
 *
 * The four-lambda constructor assumes tiles start at pixel (0, 0) and are laid
 * out at the given tile size — correct for reflow mode with no centering. When
 * the display is scaled or letterboxed (a centered reflow remainder, or the
 * fixed-grid [com.sletmoe.kotile.rendering.ScalePolicy] path), use the
 * [GridLayout]-based constructor instead: it consults the current
 * [GridLayout.tileAt], which accounts for the centering offset and the
 * on-screen (possibly scaled, possibly fractional) tile size.
 */
class KotileInputProcessor private constructor(
    private val translate: (screenX: Int, screenY: Int) -> Pair<Int, Int>?,
) : InputProcessor {

    /**
     * Creates a processor that maps pixels with the raw
     * [pixelToTile] formula (tiles at origin, no centering offset). Suitable for
     * reflow displays that are an exact tile multiple. The lambdas are read per
     * event so the current tile size / grid dimensions are always used.
     *
     * @param tileWidthPx returns the current tile width in pixels; called per event
     * @param tileHeightPx returns the current tile height in pixels; called per event
     * @param gridWidth returns the current grid width in tiles; called per event
     * @param gridHeight returns the current grid height in tiles; called per event
     */
    constructor(
        tileWidthPx: () -> Int,
        tileHeightPx: () -> Int,
        gridWidth: () -> Int,
        gridHeight: () -> Int,
    ) : this({ screenX, screenY ->
        pixelToTile(screenX, screenY, tileWidthPx(), tileHeightPx(), gridWidth(), gridHeight())
    })

    /**
     * Creates a processor that maps pixels through the current [GridLayout],
     * correctly handling centering offsets and scaled/fractional tile sizes.
     * This is the recommended constructor; pass a provider that returns the
     * canvas's live layout (e.g. `{ window.layout }`) so it stays correct across
     * resizes.
     *
     * @param layout returns the current [GridLayout]; called per event
     */
    constructor(layout: () -> GridLayout) : this({ screenX, screenY ->
        layout().tileAt(screenX.toFloat(), screenY.toFloat())
    })

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
}
