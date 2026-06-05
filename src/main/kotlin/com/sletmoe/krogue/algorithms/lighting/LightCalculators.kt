package com.sletmoe.krogue.algorithms.lighting

/**
 * Resolves a `LightEmitter.calculatorId` string to a [LightValueCalculator]. The
 * calculators are stateless, so single shared instances are reused.
 *
 * This is the minimal stand-in for the general component registry deferred to the
 * save/load phase (4f) — see the serialization convention in ARCHITECTURE.md. New
 * falloff models register their id here.
 */
object LightCalculators {
    const val DIMINISHING = "diminishing"
    const val GLOBAL = "global"

    private val byId: Map<String, LightValueCalculator> =
        mapOf(
            DIMINISHING to DiminishingLightValueCalculator(),
            GLOBAL to GlobalLightValueCalculator(),
        )

    fun resolve(calculatorId: String): LightValueCalculator =
        byId[calculatorId] ?: error("Unknown light calculator id: '$calculatorId'")
}
