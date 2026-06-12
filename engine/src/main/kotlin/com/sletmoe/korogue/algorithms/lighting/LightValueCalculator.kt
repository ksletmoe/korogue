package com.sletmoe.korogue.algorithms.lighting

import com.sletmoe.korogue.algorithms.color.NormalizedRgb

data class LightValue(val normalizedColor: NormalizedRgb, val intensity: Double) {
    companion object {
        /**
         * Uniform full-intensity white — the ambient baseline for a fully-lit zone ("global
         * illumination" / fullbright). Pass as [com.sletmoe.korogue.systems.LightingSystem]'s
         * `ambientLight` for games that don't model light per-source (e.g. Rogue): every cell is
         * lit, so map visibility reduces to line-of-sight rather than being gated on light reach.
         */
        val FULLBRIGHT = LightValue(NormalizedRgb(1.0, 1.0, 1.0), 1.0)
    }
}

interface LightValueCalculator {
    fun calculateLightValue(
        lightColor: NormalizedRgb,
        lightRadius: Double,
        distanceFromLightSource: Double,
    ): LightValue
}
