package com.sletmoe.korogue.algorithms.lighting

import com.sletmoe.korogue.algorithms.color.NormalizedRgb

data class LightValue(val normalizedColor: NormalizedRgb, val intensity: Double)

interface LightValueCalculator {
    fun calculateLightValue(
        lightColor: NormalizedRgb,
        lightRadius: Double,
        distanceFromLightSource: Double,
    ): LightValue
}
