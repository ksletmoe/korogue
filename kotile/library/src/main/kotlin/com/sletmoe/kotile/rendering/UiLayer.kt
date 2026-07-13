package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.KotileCanvas

/**
 * A persistent, input-aware element of a [UiLayer], positioned in **content-pixel**
 * space rather than snapped to the tile grid (ADR-0018). A widget is a panel, a
 * health bar between rows, a tooltip at the exact cursor pixel, a sliding button —
 * anything a *graphical* tile game draws and interacts with off the grid.
 *
 * A widget is the free-layer counterpart of the grid/text UI toolkit (`Menu`,
 * `Dialog`, …): those draw cell-aligned to a tile surface and stay the right
 * choice for ASCII/text UI; a widget draws at arbitrary pixel positions via
 * [KotileCanvas.drawSprite]. It differs from an
 * [Effect] (the other free-layer consumer) only in *lifetime and input handling* —
 * a widget is persistent and hit-testable, an effect is transient and expires —
 * not in how it renders.
 *
 * Implementations draw within [render] using the content-pixel space anchored by
 * [bounds]; [bounds] also defines the region [UiLayer] hit-tests for pointer
 * events. A widget that animates (slides, pulses) advances its state in [update]
 * and can move by returning updated [bounds].
 */
interface Widget {
    /**
     * This widget's content-pixel [PixelRect]: where it draws and the region that
     * receives pointer events. May change over time (a sliding/animated widget
     * updates it in [update]); [UiLayer] reads it live on each render and hit-test.
     */
    val bounds: PixelRect

    /**
     * When `false`, the widget is skipped for **both** rendering and pointer
     * hit-testing (as if absent, but retained in the layer). Toggle it to
     * show/hide without add/remove churn.
     */
    var visible: Boolean

    /**
     * Draws this widget into the already-open [canvas] batch via
     * [KotileCanvas.drawSprite], anchored within [bounds]. Called by
     * [UiLayer.render] only when [visible]. Must **not** call
     * [KotileCanvas.begin]/[KotileCanvas.end] — the enclosing [LayerStack] owns
     * the single batch for the frame.
     */
    fun render(canvas: KotileCanvas)

    /**
     * Advances any time-based state by [dtMs] milliseconds (a slide-in, a pulse,
     * a timed dismissal). Called by [UiLayer.update] once per frame before
     * [render]. Defaults to a no-op for static widgets.
     */
    fun update(dtMs: Long) {}

    /**
     * A pointer button was pressed at content-pixel ([px], [py]), which is within
     * this widget's [bounds] (the caller hit-tested first). Return `true` to
     * consume the event so widgets below it in the same [UiLayer] do not also
     * receive it, or `false` to let it fall through. Defaults to `false`.
     *
     * @param button a libGDX button constant from [com.badlogic.gdx.Input.Buttons]
     */
    fun onPointerDown(px: Float, py: Float, button: Int): Boolean = false

    /**
     * A pointer button was released at content-pixel ([px], [py]) over this
     * widget. Return `true` to consume. Defaults to `false`. (Release may land on
     * a different widget than the press; a widget completes a click by pairing its
     * own down/up.)
     */
    fun onPointerUp(px: Float, py: Float, button: Int): Boolean = false

    /**
     * The cursor moved to content-pixel ([px], [py]) over this widget (hover, no
     * button held). Return `true` to consume. Defaults to `false`.
     */
    fun onPointerMoved(px: Float, py: Float): Boolean = false
}

/**
 * A free (pixel-space) [Layer] of persistent, input-aware [Widget]s — the
 * graphical-UI consumer of the [KotileCanvas.drawSprite] primitive (ADR-0018),
 * and the second consumer of the free-layer model after [EffectsLayer]. Where the
 * grid/text UI toolkit (`Menu`, `Dialog`, `PagedTextList`, `TextOverlay`,
 * `HotkeyMenu`) draws cell-aligned to a tile surface and remains the right choice
 * for ASCII/text UI, a `UiLayer` positions widgets at arbitrary pixel positions —
 * panels, bars between rows, tooltips at the cursor, sliding elements — for a
 * *graphical* tile game. It does not replace or touch the grid/text toolkit.
 *
 * Stack it **above** the grid (and any effects) so widgets draw on top:
 * ```kotlin
 * val ui = UiLayer()
 * stack.add(mapRenderer.asLayer { elapsedMs }) // grid, below
 * stack.add(effects)                            // effects, above the grid
 * stack.add(ui)                                 // UI, on top of everything
 * ui.add(HealthBarWidget(...))
 * // each frame:
 * stack.update(dtMs)   // advances widget animations
 * stack.render()       // draws visible widgets via drawSprite
 * ```
 *
 * **Layering.** Widgets draw in insertion order — index 0 first (bottom), the
 * last added on top — so [add] appends to the top. Hit-testing ([widgetAt] and the
 * pointer dispatchers) walks the reverse: the **topmost** widget under the point
 * wins, matching what the user sees.
 *
 * **Input.** A `UiLayer` hit-tests in content-pixel space; wire it to input by
 * forwarding a [com.sletmoe.kotile.input.KotileInputListener]'s pixel events —
 * `onPointerDown` → [onPointerDown], etc. (those arrive already mapped to content
 * pixels via [GridLayout.contentPixelAt]). This mirrors how the grid UI receives
 * translated *tile* events; the layer itself stays free of the input package.
 *
 * Like [EffectsLayer], a `UiLayer` manages only its own widgets and never adds or
 * removes layers from the enclosing [LayerStack], so it respects the stack's
 * no-structural-modification-mid-frame rule.
 */
class UiLayer : Layer {
    private val mutableWidgets = mutableListOf<Widget>()

    /** The widgets in back-to-front draw order (index 0 is drawn first, at the bottom). */
    val widgets: List<Widget> get() = mutableWidgets

    /** Number of widgets in the layer (visible or not). */
    val widgetCount: Int get() = mutableWidgets.size

    /** Appends [widget] to the top of the layer (drawn last, over everything below) and returns it. */
    fun add(widget: Widget): Widget {
        mutableWidgets.add(widget)
        return widget
    }

    /** Inserts [widget] at [index] in the draw order (0 = bottom). */
    fun add(index: Int, widget: Widget) {
        mutableWidgets.add(index, widget)
    }

    /** Removes [widget] from the layer. Returns `true` if it was present. */
    fun remove(widget: Widget): Boolean = mutableWidgets.remove(widget)

    /** Removes every widget. */
    fun clear() {
        mutableWidgets.clear()
    }

    /** Advances every widget's time-based state by [dtMs] milliseconds, in draw order. */
    override fun update(dtMs: Long) {
        for (widget in mutableWidgets) {
            widget.update(dtMs)
        }
    }

    /** Draws every [Widget.visible] widget via its [Widget.render], back-to-front. */
    override fun render(canvas: KotileCanvas) {
        for (widget in mutableWidgets) {
            if (widget.visible) widget.render(canvas)
        }
    }

    /**
     * The topmost visible widget whose [Widget.bounds] contain content-pixel
     * ([px], [py]), or `null` if none — the pixel-space equivalent of
     * [GridLayout.tileAt] for free-positioned UI. Walks widgets front-to-back
     * (last-added first) so the widget the user sees on top is the one returned.
     */
    fun widgetAt(px: Float, py: Float): Widget? {
        for (i in mutableWidgets.indices.reversed()) {
            val widget = mutableWidgets[i]
            if (widget.visible && widget.bounds.contains(px, py)) return widget
        }
        return null
    }

    /**
     * Dispatches a pointer press at content-pixel ([px], [py]) to the visible
     * widgets under it, **topmost first**, stopping at the first that consumes it
     * (returns `true` from [Widget.onPointerDown]). A widget that returns `false`
     * lets the press fall through to the widget below. Returns whether any widget
     * consumed it (so the caller can suppress a world/grid click). Wire this to
     * [com.sletmoe.kotile.input.KotileInputListener.onPointerDown].
     *
     * A handler that structurally changes this layer (e.g. a close button removing
     * a dialog) should **consume** the event so dispatch stops before the mutated
     * list is walked further.
     */
    fun onPointerDown(px: Float, py: Float, button: Int): Boolean =
        dispatch(px, py) { it.onPointerDown(px, py, button) }

    /**
     * Dispatches a pointer release at content-pixel ([px], [py]) to the visible
     * widgets under it, topmost first, stopping at the first that consumes it via
     * [Widget.onPointerUp]. Returns whether any widget consumed it. Wire this to
     * [com.sletmoe.kotile.input.KotileInputListener.onPointerUp].
     */
    fun onPointerUp(px: Float, py: Float, button: Int): Boolean =
        dispatch(px, py) { it.onPointerUp(px, py, button) }

    /**
     * Dispatches a cursor move at content-pixel ([px], [py]) to the visible
     * widgets under it, topmost first, stopping at the first that consumes it via
     * [Widget.onPointerMoved]. Returns whether any widget consumed it. Wire this
     * to [com.sletmoe.kotile.input.KotileInputListener.onPointerMoved].
     */
    fun onPointerMoved(px: Float, py: Float): Boolean =
        dispatch(px, py) { it.onPointerMoved(px, py) }

    /**
     * Delivers a pointer event to the visible widgets covering ([px], [py]),
     * topmost first (last-added first), stopping at — and returning `true` for —
     * the first whose [deliver] consumes it; `false` if none do. Shared by the
     * `onPointer*` dispatchers so they agree on hit order and fall-through.
     */
    private inline fun dispatch(px: Float, py: Float, deliver: (Widget) -> Boolean): Boolean {
        for (i in mutableWidgets.indices.reversed()) {
            val widget = mutableWidgets[i]
            if (widget.visible && widget.bounds.contains(px, py) && deliver(widget)) return true
        }
        return false
    }
}
