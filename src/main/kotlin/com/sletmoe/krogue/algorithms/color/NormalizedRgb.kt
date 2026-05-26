package com.sletmoe.krogue.algorithms.color

import java.awt.Color
import kotlin.math.min

// represent RGB data in a min-max normalized form
data class NormalizedRgb(val r: Double, val g: Double, val b: Double) {
    fun toColor(): Color =
        Color(
            min((r * 255).toInt(), 255),
            min((g * 255).toInt(), 255),
            min((b * 255).toInt(), 255),
        )

    // for color multiplication https://en.wikipedia.org/wiki/Blend_modes#Multiply
    operator fun times(other: NormalizedRgb): NormalizedRgb = NormalizedRgb(r * other.r, g * other.g, b * other.b)

    operator fun times(multiplier: Double): NormalizedRgb =
        NormalizedRgb(r * multiplier, g * multiplier, b * multiplier)
}

fun Color.toNormalizedRgb(): NormalizedRgb =
    NormalizedRgb(red.toDouble() / 255.0, green.toDouble() / 255.0, blue.toDouble() / 255.0)
