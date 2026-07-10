package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.KotileCanvas

/**
 * A single pass of a composited frame (ADR-0018).
 *
 * A frame is an ordered list of layers drawn back-to-front (painter's
 * algorithm) into one shared [KotileCanvas] batch — see [LayerStack]. Overlaying
 * a grid layer with a free (pixel-space) layer is therefore just their order in
 * that list: draw the grid pass, then the free pass on top. There is no
 * blend/composite machinery and no per-layer framebuffer.
 *
 * Two layer kinds ride on this one interface, distinguished by how they
 * *address* content, not by how they render:
 * - **Grid layers** — cell-addressed content. Obtain one from a grid renderer's
 *   `asLayer(...)` (see [TileRenderer] and
 *   [com.sletmoe.kotile.display.ascii.AsciiTileWindow]).
 * - **Free layers** — pixel-addressed content drawn with [KotileCanvas.drawSprite]
 *   at float positions (effects, projectiles, pixel-space UI).
 *
 * [render] runs **between** the canvas's `begin`/`end`; implementations must not
 * call either — the [LayerStack] owns the single batch for the frame.
 *
 * A functional interface, so a stateless free layer can be written as a lambda:
 * `Layer { canvas -> canvas.drawSprite(...) }`. Stateful layers (with [update])
 * implement it as a class.
 */
fun interface Layer {
    /**
     * Draws this layer's content into the already-open [canvas] batch. Called
     * by [LayerStack.render] in back-to-front order. Must **not** call
     * [KotileCanvas.begin] or [KotileCanvas.end].
     */
    fun render(canvas: KotileCanvas)

    /**
     * Advances any time-based state by [dtMs] milliseconds (particle motion,
     * lifetime expiry). Called by [LayerStack.update] once per frame before
     * [render]. Defaults to a no-op for static layers.
     */
    fun update(dtMs: Long) {}
}
