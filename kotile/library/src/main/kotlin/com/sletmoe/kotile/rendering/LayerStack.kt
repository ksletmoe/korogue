package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.KotileCanvas

/**
 * Composites an ordered list of [Layer]s into a single [KotileCanvas] frame
 * (ADR-0018).
 *
 * The stack owns the one `begin`/`end` pair for the frame: [render] opens the
 * batch, draws every layer **back-to-front** (index 0 first, so later layers
 * paint on top), then closes it. Overlaying grid and free layers is therefore
 * just their order in [layers] — no compositing passes, no per-layer buffers.
 *
 * All layers in a stack share this one [canvas]; a grid renderer used as a layer
 * (`asLayer(...)`) must have been built on the same canvas.
 *
 * Typical use:
 * ```kotlin
 * val stack = LayerStack(canvas)
 * stack.add(mapRenderer.asLayer { elapsedMs })   // grid, drawn first (bottom)
 * stack.add(effects)                              // free layer, drawn on top
 * // per frame:
 * stack.update(dtMs)
 * stack.render()
 * ```
 *
 * @property canvas the shared canvas every layer draws into
 */
class LayerStack(val canvas: KotileCanvas) {
    private val mutableLayers = mutableListOf<Layer>()

    /** The layers in back-to-front draw order (index 0 is drawn first, at the bottom). */
    val layers: List<Layer> get() = mutableLayers

    /** Appends [layer] to the top of the stack (drawn last, over everything below). */
    fun add(layer: Layer) {
        mutableLayers.add(layer)
    }

    /** Inserts [layer] at [index] in the draw order (0 = bottom). */
    fun add(
        index: Int,
        layer: Layer,
    ) {
        mutableLayers.add(index, layer)
    }

    /** Removes [layer] from the stack. Returns `true` if it was present. */
    fun remove(layer: Layer): Boolean = mutableLayers.remove(layer)

    /** Removes every layer. */
    fun clear() {
        mutableLayers.clear()
    }

    /**
     * Advances every layer's time-based state by [dtMs] milliseconds, in draw
     * order. Call once per frame before [render].
     *
     * A layer must not structurally modify this stack from its
     * [Layer.update]/[Layer.render] (add or remove layers); do that between
     * frames. A layer with a finite lifetime should signal completion and let
     * its owner remove it. (Effects that expire *individual items* manage those
     * internally and do not touch the stack — see the effects layer.)
     */
    fun update(dtMs: Long) {
        for (layer in mutableLayers) {
            layer.update(dtMs)
        }
    }

    /**
     * Draws every layer into [canvas] back-to-front within a single
     * `begin`/`end` pass. As with [update], layers must not add/remove layers
     * mid-frame.
     */
    fun render() {
        canvas.begin()
        for (layer in mutableLayers) {
            layer.render(canvas)
        }
        canvas.end()
    }
}
