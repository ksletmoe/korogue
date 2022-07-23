package com.sletmoe.krogue.algorithms.lighting

import com.sletmoe.krogue.algorithms.color.NormalizedRgb

class DiminishingLightValueCalculator : LightValueCalculator {
    override fun calculateLightValue(
        lightColor: NormalizedRgb, lightRadius: Double, distanceFromLightSource: Double
    ) : LightValue {
        val intensity = if (lightRadius < distanceFromLightSource) {
            0.0
        } else {
            1.0 - (distanceFromLightSource - 1.0) / lightRadius
        }

        return LightValue(lightColor, intensity)
    }
}
