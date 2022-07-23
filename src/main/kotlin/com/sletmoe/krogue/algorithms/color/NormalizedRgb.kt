package com.sletmoe.krogue.algorithms.color

import java.awt.Color

// represent RGB data in a min-max normalized form
data class NormalizedRgb(val r: Double, val g: Double, val b: Double) {
    fun toColor(): Color = Color((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    // for color multiplication https://en.wikipedia.org/wiki/Blend_modes#Multiply
    operator fun times(other: NormalizedRgb): NormalizedRgb =
        NormalizedRgb(r * other.r, g * other.g, b * other.b)
}

fun Color.toNormalizedRgb(): NormalizedRgb =
    NormalizedRgb(red.toDouble() / 255.0, green.toDouble() / 255.0, blue.toDouble() / 255.0)
