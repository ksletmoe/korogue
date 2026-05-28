package com.sletmoe.krogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import kotlin.math.min

// represent RGB data in a min-max normalized form
data class NormalizedRgb(val r: Double, val g: Double, val b: Double) {
    fun toColor(): Color =
        Color(
            min(r.toFloat(), 1f),
            min(g.toFloat(), 1f),
            min(b.toFloat(), 1f),
            1f,
        )

    // for color multiplication https://en.wikipedia.org/wiki/Blend_modes#Multiply
    operator fun times(other: NormalizedRgb): NormalizedRgb = NormalizedRgb(r * other.r, g * other.g, b * other.b)

    operator fun times(multiplier: Double): NormalizedRgb =
        NormalizedRgb(r * multiplier, g * multiplier, b * multiplier)
}

fun Color.toNormalizedRgb(): NormalizedRgb = NormalizedRgb(r.toDouble(), g.toDouble(), b.toDouble())
