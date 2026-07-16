package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A [TileSurface] view restricted to [rect] of [delegate] (ADR-0011): local (0, 0) maps to
 * `rect.(x, y)`, writes are clipped to the rect, [zOffset] is added to every layer index, and
 * colors are scaled by [dim]. This is how a widget draws in coordinates local to its own bounds,
 * on its layer's z-band, without knowing where it sits on screen.
 *
 * [dim] < 1 darkens output — used by [UiRoot] to render the layers beneath an open modal dialog
 * dimmed while the (opaque) dialog draws at full brightness (the dim-behind effect). It relies on
 * the dialog's cells carrying **opaque** backgrounds: those win their cell outright, so nothing
 * underneath shows through. Backgrounds are resolved per channel (ADR-0030), not alpha-blended, so
 * a dialog cell that passed a *transparent* background would now show the dimmed layer beneath it
 * rather than the dialog's own fill. [dimmed] scales rgb and preserves alpha, so an opaque cell
 * stays opaque through the dimming and the effect is unaffected.
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
