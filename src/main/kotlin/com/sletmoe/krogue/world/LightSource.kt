package com.sletmoe.krogue.world

import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.lighting.LightValue
import com.sletmoe.krogue.algorithms.lighting.LightValueCalculator
import java.awt.Color

class LightSource(
    position: ZonalPosition,
    name: String,
    color: Color,
    var lightRadius: Double,
    private val lightValueCalculator: LightValueCalculator,
    glyph: Char = ' ',
    description: String? = null,
) : MovableEntity(position, name, glyph, color, description) {
    fun calculateLightValue(distanceFromSource: Double): LightValue =
        lightValueCalculator.calculateLightValue(color.toNormalizedRgb(), lightRadius, distanceFromSource)
}
