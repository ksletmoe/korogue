package com.sletmoe.krogue.algorithms.color

import java.awt.Color

typealias ColorTransformer = (Color) -> Color

fun multiplicationTransformer(multiplier: Color): ColorTransformer = { ColorBlending.multiply(it, multiplier) }

fun multiplicationTransformer(multiplier: Double): ColorTransformer = {
    (it.toNormalizedRgb() * multiplier).toColor()
}
