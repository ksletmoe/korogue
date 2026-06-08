package com.sletmoe.krogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect

/**
 * A [TileSurface] view restricted to [rect] of [delegate] (ADR-0011): local (0, 0) maps to
 * `rect.(x, y)`, writes are clipped to the rect, [zOffset] is added to every layer index, and
 * colors are scaled by [dim]. This is how a widget draws in coordinates local to its own bounds,
 * on its layer's z-band, without knowing where it sits on screen.
 *
 * [dim] < 1 darkens output — used by [UiRoot] to render the layers beneath an open modal dialog
 * dimmed while the (opaque) dialog draws at full brightness (the dim-behind effect, top-cell-wins,
 * no alpha blending).
 */
class RegionSurface(
    private val delegate: TileSurface,
    private val rect: IntRect,
    private val zOffset: Int = 0,
    private val dim: Float = 1f,
) : TileSurface {
    override val width: Int get() = rect.width
    override val height: Int get() = rect.height

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    ) {
        if (x < 0 || x >= rect.width || y < 0 || y >= rect.height) return
        delegate.put(rect.x + x, rect.y + y, zOffset + z, glyph, dimmed(fg), dimmed(bg))
    }

    private fun dimmed(color: Color): Color =
        if (dim >= 1f) color else Color(color.r * dim, color.g * dim, color.b * dim, color.a)
}
