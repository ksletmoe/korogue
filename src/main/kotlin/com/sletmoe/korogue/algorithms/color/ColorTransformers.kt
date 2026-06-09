package com.sletmoe.korogue.algorithms.color

import com.badlogic.gdx.graphics.Color

typealias ColorTransformer = (Color) -> Color

fun multiplicationTransformer(multiplier: Color): ColorTransformer = { ColorBlending.multiply(it, multiplier) }

fun multiplicationTransformer(multiplier: Double): ColorTransformer =
    {
        (it.toNormalizedRgb() * multiplier).toColor()
    }
