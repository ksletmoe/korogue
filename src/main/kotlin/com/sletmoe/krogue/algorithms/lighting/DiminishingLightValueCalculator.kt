package com.sletmoe.krogue.algorithms.lighting

import com.sletmoe.krogue.algorithms.color.NormalizedRgb

class DiminishingLightValueCalculator : LightValueCalculator {
    override fun calculateLightValue(
        lightColor: NormalizedRgb, lightRadius: Double, distanceFromLightSource: Double
    ) : LightValue {
        val intensity = if (lightRadius < distanceFromLightSource) {
            0.0
        } else {
            val minLightValue = lightRadius * MINIMUM_LIGHT_RATIO
            1.0 - (distanceFromLightSource - minLightValue) / lightRadius
        }

        return LightValue(lightColor, intensity)
    }

    companion object {
        private const val MINIMUM_LIGHT_RATIO = 0.10
    }
}
