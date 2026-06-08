package com.sletmoe.krogue.registry

import com.sletmoe.krogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.krogue.algorithms.lighting.GlobalLightValueCalculator
import com.sletmoe.krogue.algorithms.lighting.LightValueCalculator
import com.sletmoe.krogue.systems.BehaviorStrategy
import com.sletmoe.krogue.systems.HuntPlayerStrategy
import com.sletmoe.krogue.systems.WanderStrategy

/**
 * The single seam describing a game's pluggable pieces to the engine (ADR-0009, hybrid
 * option iii). It bundles the per-concern registries — [strategies] (AI) and [calculators]
 * (lighting) — so a consumer configures everything in one place
 * (`GameModule.engineDefaults().strategy(...).build()`), while systems still depend only on
 * the narrow `Registry` they need (`module.strategies::resolve`), never the whole module.
 *
 * The component-serialization registry joins here in 4f step 3 (the CBOR codec), which is
 * why this is a module rather than loose registries.
 */
class GameModule private constructor(
    val strategies: Registry<BehaviorStrategy>,
    val calculators: Registry<LightValueCalculator>,
) {
    class Builder internal constructor(
        private val strategies: MutableMap<String, BehaviorStrategy>,
        private val calculators: MutableMap<String, LightValueCalculator>,
    ) {
        /** Register (or override) an AI strategy under [id]. */
        fun strategy(
            id: String,
            strategy: BehaviorStrategy,
        ): Builder = apply { strategies[id] = strategy }

        /** Register (or override) a light calculator under [id]. */
        fun calculator(
            id: String,
            calculator: LightValueCalculator,
        ): Builder = apply { calculators[id] = calculator }

        fun build(): GameModule = GameModule(Registry(strategies.toMap()), Registry(calculators.toMap()))
    }

    companion object {
        /** A builder pre-loaded with the engine's built-in strategies and calculators. */
        fun engineDefaults(): Builder =
            Builder(
                strategies =
                    mutableMapOf(
                        WanderStrategy.ID to WanderStrategy(),
                        HuntPlayerStrategy.ID to HuntPlayerStrategy(),
                    ),
                calculators =
                    mutableMapOf(
                        DiminishingLightValueCalculator.ID to DiminishingLightValueCalculator(),
                        GlobalLightValueCalculator.ID to GlobalLightValueCalculator(),
                    ),
            )
    }
}
