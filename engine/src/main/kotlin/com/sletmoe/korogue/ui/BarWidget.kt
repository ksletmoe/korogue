package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import kotlin.math.roundToInt

/** A current/max pair for a [BarWidget] (e.g. hit points, mana). */
data class BarValue(
    val current: Int,
    val max: Int,
)

/**
 * A single-row stat bar (ADR-0011): `LABEL ████████░░ cur/max`. Reads its [value] each frame
 * (via the supplied function) so it always reflects live state — point it at the player's health
 * for an HP bar, mana for a mana bar, etc. Filled cells use [filled], the remainder [empty].
 *
 * Drawn on the bar's own row (y = 0 of its [bounds]); the bar shrinks to fit between the label
 * prefix and the `cur/max` suffix, and a zero/negative max renders empty rather than dividing.
 */
class BarWidget(
    override val bounds: IntRect,
    private val label: String,
    private val filled: Color,
    private val empty: Color,
    private val labelColor: Color = Color.WHITE,
    private val z: Int = 0,
    private val value: () -> BarValue,
) : Widget {
    override fun draw(surface: TileSurface) {
        val v = value()
        val prefix = if (label.isEmpty()) "" else "$label "
        val suffix = " ${v.current}/${v.max}"

        surface.text(0, 0, z, prefix, labelColor)

        val barStart = prefix.length
        val barWidth = (surface.width - prefix.length - suffix.length).coerceAtLeast(0)
        val fraction = if (v.max <= 0) 0.0 else (v.current.toDouble() / v.max).coerceIn(0.0, 1.0)
        val filledCells = (barWidth * fraction).roundToInt()
        for (i in 0 until barWidth) {
            surface.put(barStart + i, 0, z, Cp437.FULL_BLOCK, if (i < filledCells) filled else empty, Color.BLACK)
        }

        surface.text((barStart + barWidth).coerceAtMost(surface.width), 0, z, suffix, labelColor)
    }
}
