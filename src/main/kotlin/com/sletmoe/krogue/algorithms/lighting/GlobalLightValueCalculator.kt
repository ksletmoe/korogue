package com.sletmoe.krogue.algorithms.lighting

import com.sletmoe.krogue.algorithms.color.NormalizedRgb

class GlobalLightValueCalculator : LightValueCalculator {
    override fun calculateLightValue(
        lightColor: NormalizedRgb,
        lightRadius: Double,
        distanceFromLightSource: Double,
    ): LightValue {
        return LightValue(lightColor, 1.0)
    }

    companion object {
        const val ID = "global"
    }
}
