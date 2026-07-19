package com.sletmoe.kotile.input

/**
 * Receives translated input events from a [KotileInputProcessor].
 *
 * Mouse coordinates delivered here use **tile coordinates** (not pixels) with a
 * **top-left origin**: tile (0, 0) is the top-left cell, x increases rightward,
 * and y increases downward. libGDX Desktop (`InputProcessor` on LWJGL3) also
 * uses a top-left origin for mouse pixel positions (unlike GL draw coordinates
 * which are bottom-left), so [KotileInputProcessor] only divides by the current
 * tile dimensions — no axis flip is required.
 *
 * Key codes use libGDX constants from [com.badlogic.gdx.Input.Keys] directly —
 * no intermediate enum is introduced, which avoids mapping churn and lets
 * consumers use the full libGDX key set.
 *
 * **Out-of-bounds mouse events** (e.g. clicks in a letterboxed area when
 * `fitToWindow = false`, or outside the pixel area occupied by tiles) are
 * silently dropped by [KotileInputProcessor] and will not produce calls to
 * [onTileClicked] or [onTileHovered].
 *
 * **Adapter**: most listeners only need a subset of these events. Extend
 * [KotileInputAdapter] instead of this interface to avoid implementing unused
 * methods.
 *
 * ### Why listener interface rather than Flow/Channel
 *
 * The listener model keeps the input layer dependency-free (no coroutines
 * runtime required) and matches the idiomatic libGDX pattern that existing
 * consumers already understand. Consumers that want a Flow can trivially wrap
 * a [KotileInputAdapter] in a `callbackFlow` on their side; the reverse
 * (bridging a Flow into a libGDX-style interface) is more awkward. A
 * Flow-first design is a natural follow-on once the API settles.
 */
interface KotileInputListener {
    /**
     * Called when a key is pressed down.
     *
     * @param keycode a libGDX key constant from [com.badlogic.gdx.Input.Keys]
     */
    fun onKeyDown(keycode: Int)

    /**
     * Called when a key is released.
     *
     * @param keycode a libGDX key constant from [com.badlogic.gdx.Input.Keys]
     */
    fun onKeyUp(keycode: Int)

    /**
     * Called when a key produces a printable character.
     *
     * @param character the typed character
     */
    fun onKeyTyped(character: Char)

    /**
     * Called when a mouse button is pressed over a valid tile cell.
     *
     * The coordinates use the **top-left origin** tile coordinate system:
     * (0, 0) is the top-left cell; x increases rightward; y increases downward.
     * Events outside the tile grid are dropped.
     *
     * @param tileX tile column (0 = left edge)
     * @param tileY tile row (0 = top edge)
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    fun onTileClicked(
        tileX: Int,
        tileY: Int,
        button: Int,
    )

    /**
     * Called when the mouse cursor moves over a new tile cell (no button held).
     *
     * The coordinates use the **top-left origin** tile coordinate system.
     * Events outside the tile grid are dropped.
     *
     * @param tileX tile column (0 = left edge)
     * @param tileY tile row (0 = top edge)
     */
    fun onTileHovered(
        tileX: Int,
        tileY: Int,
    )

    /**
     * Called when the mouse is dragged (moved with a button held) over a tile cell.
     *
     * The coordinates use the **top-left origin** tile coordinate system.
     * Events outside the tile grid are dropped.
     *
     * **Note**: libGDX's `touchDragged` callback does not report which button
     * is held, so [button] is always `-1` when this method is called via
     * [KotileInputProcessor]. Track button state in [onTileClicked] and a
     * corresponding `touchUp` handler if you need it.
     *
     * @param tileX tile column (0 = left edge)
     * @param tileY tile row (0 = top edge)
     * @param button `-1` (libGDX does not supply the button in drag events)
     */
    fun onTileDragged(
        tileX: Int,
        tileY: Int,
        button: Int,
    )

    /**
     * Called when the mouse scroll wheel moves.
     *
     * @param amountX horizontal scroll delta (positive = right)
     * @param amountY vertical scroll delta (positive = up in libGDX convention)
     */
    fun onScrolled(
        amountX: Float,
        amountY: Float,
    )

    // ── Free (pixel-space) pointer events ─────────────────────────────────

    /**
     * Called when a mouse button is pressed, in **content-pixel** coordinates —
     * the free/pixel-space hit-testing space for graphical UI and effects
     * (ADR-0018), matching [com.sletmoe.kotile.rendering.GridLayout.contentPixelAt]
     * and [com.sletmoe.kotile.display.KotileCanvas.drawSprite]. Fires *alongside*
     * [onTileClicked] on the same press; grid/text UI uses the tile events, free-
     * positioned widgets use these. Events outside the grid content rectangle are
     * dropped, as with the tile events.
     *
     * Defaults to a no-op so grid/text-only consumers are unaffected; override it
     * (or forward to a [com.sletmoe.kotile.rendering.UiLayer]) only for pixel-
     * space UI.
     *
     * @param px content-pixel x (0 = content left edge, increases right)
     * @param py content-pixel y (0 = content top edge, increases down)
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    fun onPointerDown(
        px: Float,
        py: Float,
        button: Int,
    ) {}

    /**
     * Called when a mouse button is released, in **content-pixel** coordinates.
     * Unlike [onTileClicked]/[onPointerDown] (delivered on press), there is no
     * tile-coordinate equivalent — release completes a free-UI click. Events
     * outside the grid content rectangle are dropped. Defaults to a no-op.
     *
     * @param px content-pixel x; @param py content-pixel y
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    fun onPointerUp(
        px: Float,
        py: Float,
        button: Int,
    ) {}

    /**
     * Called when the mouse cursor moves (no button held), in **content-pixel**
     * coordinates — the free-UI counterpart to [onTileHovered]. Events outside the
     * grid content rectangle are dropped. Defaults to a no-op.
     *
     * @param px content-pixel x; @param py content-pixel y
     */
    fun onPointerMoved(
        px: Float,
        py: Float,
    ) {}

    /**
     * Called when the mouse is dragged (moved with a button held), in
     * **content-pixel** coordinates — the free-UI counterpart to [onTileDragged].
     * As with that event, libGDX does not report which button is held. Events
     * outside the grid content rectangle are dropped. Defaults to a no-op.
     *
     * @param px content-pixel x; @param py content-pixel y
     */
    fun onPointerDragged(
        px: Float,
        py: Float,
    ) {}
}
