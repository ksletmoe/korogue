package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile

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

    /**
     * Forwards a (possibly time-varying) [tile] to the [delegate] with the same coordinate/z
     * translation and dimming as the glyph overload, so a widget's animated cell survives being
     * drawn through its own [RegionSurface] (ADR-0033) — including the map, which [UiRoot] always
     * wraps in one.
     *
     * With [dim] `>= 1f` the tile passes through untouched, so a [DynamicAsciiTile] reaches the
     * window as-is and animates. When dimming (a layer beneath an open modal), a *dynamic* tile is
     * wrapped so it keeps animating with each resolved frame dimmed, while a *static* tile is
     * resolved and dimmed in place — never promoting a static cell to a dynamic one (which would
     * make the window needlessly repaint it every frame).
     */
    override fun put(
        x: Int,
        y: Int,
        z: Int,
        tile: AsciiTile,
    ) {
        if (x < 0 || x >= rect.width || y < 0 || y >= rect.height) return
        delegate.put(rect.x + x, rect.y + y, zOffset + z, dimmedTile(tile))
    }

    private fun dimmedTile(tile: AsciiTile): AsciiTile =
        when {
            dim >= 1f -> tile
            tile is DynamicAsciiTile -> DimmingAsciiTile(tile)
            else -> dimmed(tile.resolveAt(0))
        }

    private fun dimmed(tile: StaticAsciiTile): StaticAsciiTile =
        StaticAsciiTile(tile.character, dimmed(tile.foregroundColor), dimmed(tile.backgroundColor))

    private fun dimmed(color: Color): Color =
        if (dim >= 1f) color else Color(color.r * dim, color.g * dim, color.b * dim, color.a)

    /**
     * Wraps a [DynamicAsciiTile] so it stays dynamic (the window keeps resolving it per frame) but
     * every resolved frame comes back dimmed by [dim]. Used only for the dim-behind-modal path.
     * Caches the dimmed result when the underlying frame identity hasn't changed (by reference
     * equality), avoiding per-call Color/StaticAsciiTile allocation when the inner tile's frame
     * is stable across consecutive resolveAt calls.
     */
    private inner class DimmingAsciiTile(
        private val inner: DynamicAsciiTile,
    ) : DynamicAsciiTile {
        private var cachedSourceFrame: StaticAsciiTile? = null
        private var cachedDimmedFrame: StaticAsciiTile? = null

        override fun resolveAt(elapsedMs: Long): StaticAsciiTile {
            val sourceFrame = inner.resolveAt(elapsedMs)
            // Cache hit: the inner tile resolved to the same frame instance as last time (common
            // when an AnimatedAsciiTile sits mid-frame, or a static tile is wrapped), so reuse
            // the previously dimmed result instead of allocating fresh Color/StaticAsciiTile.
            if (sourceFrame === cachedSourceFrame) {
                return cachedDimmedFrame!!
            }
            // Cache miss: compute the dimmed frame and cache both the source identity and result.
            val dimmedFrame = dimmed(sourceFrame)
            cachedSourceFrame = sourceFrame
            cachedDimmedFrame = dimmedFrame
            return dimmedFrame
        }
    }
}
