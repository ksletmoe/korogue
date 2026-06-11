package com.sletmoe.korogue.ui

/**
 * The root of the UI toolkit (ADR-0011): an ordered stack of widget layers composited into one
 * [TileSurface]. Layers are added bottom-to-top; each draws on its own z-band ([LAYER_Z_SPAN]
 * slots) so a higher layer always draws over a lower one.
 *
 * **Modals.** A layer added with `modal = true` captures input: keys go only to it (and any layers
 * above it), and it swallows keys it doesn't handle so they never reach gameplay. Layers *below*
 * the topmost modal are rendered dimmed ([dimFactor]) — the opaque-dialog + dim-behind effect.
 *
 * **Input fall-through.** With no modal open, [handleKey] offers a key to layers top-down and
 * returns false if none consumed it, so the caller (the game loop) can then treat it as gameplay.
 */
class UiRoot(
    private val surface: TileSurface,
    private val dimFactor: Float = DEFAULT_DIM_FACTOR,
) {
    private data class Layer(
        val widget: Widget,
        val modal: Boolean,
    )

    private val layers = ArrayList<Layer>()

    /** Adds [widget] on top of the stack. A [modal] layer captures input and dims the layers below it. */
    fun add(
        widget: Widget,
        modal: Boolean = false,
    ) {
        layers.add(Layer(widget, modal))
    }

    /** Removes [widget] from the stack (e.g. closing a dialog). No-op if absent. */
    fun remove(widget: Widget) {
        layers.removeAll { it.widget === widget }
    }

    /** True if any modal layer is currently open. */
    val hasModal: Boolean get() = layers.any { it.modal }

    /** Draws every layer into [surface], bottom-up, dimming layers beneath the topmost modal. */
    fun render() {
        val topModal = layers.indexOfLast { it.modal }
        layers.forEachIndexed { index, layer ->
            val dim = if (topModal >= 0 && index < topModal) dimFactor else 1f
            layer.widget.draw(RegionSurface(surface, layer.widget.bounds, zOffset = index * LAYER_Z_SPAN, dim = dim))
        }
    }

    /**
     * Routes [keycode] to the stack. With a modal open, only the modal and layers above it are
     * offered the key and the modal swallows it regardless (returns true). Otherwise layers are
     * tried top-down and the result is whether any consumed it (false ⇒ caller handles as gameplay).
     */
    fun handleKey(keycode: Int): Boolean {
        val topModal = layers.indexOfLast { it.modal }
        for (index in layers.indices.reversed()) {
            if (topModal >= 0 && index < topModal) break
            if (layers[index].widget.handleKey(keycode)) return true
        }
        return topModal >= 0
    }

    companion object {
        /** z-index slots reserved per layer; a widget may use local z `0 until LAYER_Z_SPAN`. */
        const val LAYER_Z_SPAN = 10
        private const val DEFAULT_DIM_FACTOR = 0.4f
    }
}
