package com.sletmoe.krogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect

/**
 * An opaque modal box (ADR-0011): fills its [bounds] with [bg], draws a [Frame] (border + [title])
 * and its [content] inside the border. Added to a [UiRoot] with `modal = true`, so the root routes
 * input to it and renders the layers beneath it dimmed (the dim-behind effect) — the dialog itself
 * is opaque, so what's behind is darkened, not blended through.
 *
 * Input is forwarded to [content] (e.g. a [Menu]); if [onCancel] is set, Escape invokes it (used to
 * close a dismissible dialog — a game-over dialog leaves it null so the player must choose).
 */
class Dialog(
    override val bounds: IntRect,
    title: String?,
    private val content: Widget,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val onCancel: (() -> Unit)? = null,
) : Widget {
    private val frame = Frame(IntRect(0, 0, bounds.width, bounds.height), title, fg, bg, z)
    private val innerRect = IntRect(0, 0, bounds.width, bounds.height).inset(1)

    override fun draw(surface: TileSurface) {
        surface.fill(z, ' ', fg, bg) // opaque background
        frame.draw(surface)
        content.draw(RegionSurface(surface, innerRect, zOffset = z))
    }

    override fun handleKey(keycode: Int): Boolean {
        if (onCancel != null && keycode == Input.Keys.ESCAPE) {
            onCancel.invoke()
            return true
        }
        return content.handleKey(keycode)
    }
}
