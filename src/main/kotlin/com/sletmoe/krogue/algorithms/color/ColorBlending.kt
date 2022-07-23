package com.sletmoe.krogue.algorithms.color

import java.awt.Color

object ColorBlending {
    // https://en.wikipedia.org/wiki/Blend_modes#Multiply
    fun multiply(a: Color, b: Color): Color {
        return (a.toNormalizedRgb() * b.toNormalizedRgb()).toColor()
    }
}
