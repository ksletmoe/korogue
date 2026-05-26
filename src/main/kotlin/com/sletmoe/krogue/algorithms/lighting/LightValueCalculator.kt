package com.sletmoe.krogue.algorithms.lighting

import com.sletmoe.krogue.algorithms.color.NormalizedRgb

data class LightValue(val normalizedColor: NormalizedRgb, val intensity: Double)

interface LightValueCalculator {
    fun calculateLightValue(
        lightColor: NormalizedRgb,
        lightRadius: Double,
        distanceFromLightSource: Double,
    ): LightValue
}
