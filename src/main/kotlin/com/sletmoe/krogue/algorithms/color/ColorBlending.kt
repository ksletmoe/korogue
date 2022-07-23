package com.sletmoe.krogue.algorithms.color

import java.awt.Color
import kotlin.math.max

object ColorBlending {
    // https://en.wikipedia.org/wiki/Blend_modes#Multiply
    fun multiply(a: Color, b: Color): Color {
        return (a.toNormalizedRgb() * b.toNormalizedRgb()).toColor()
    }

    // https://en.wikipedia.org/wiki/Blend_modes#Screen
    fun screen(a: Color, b: Color): Color = screen(a.toNormalizedRgb(), b.toNormalizedRgb()).toColor()

    fun screen(a: NormalizedRgb, b: NormalizedRgb): NormalizedRgb {
        return NormalizedRgb(screen(a.r, b.r), screen(a.g, b.g), screen(a.b, b.b))
    }

    fun screen(a: Double, b: Double): Double = 1.0 - (1.0 - a) * (1.0 - b)

    fun softLight(a: Color, b: Color): Color = softLight(a.toNormalizedRgb(), b.toNormalizedRgb()).toColor()

    fun softLight(a: NormalizedRgb, b: NormalizedRgb): NormalizedRgb {
        return NormalizedRgb(softLight(a.r, b.r), softLight(a.g, b.g), softLight(a.b, b.b))
    }

    // pegtop's soft light algo: http://www.pegtop.net/delphi/articles/blendmodes/softlight.htm
    private fun softLight(a: Double, b: Double): Double = (1.0 - a) * a * b + a * screen(a, b)

    fun lightenOnly(a: NormalizedRgb, b: NormalizedRgb): NormalizedRgb {
        return NormalizedRgb(max(a.r, b.r), max(a.g, b.g), max(a.b, b.b))
    }
}
