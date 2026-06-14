package com.sletmoe.korogue.registry

import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.GlobalLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.LightValueCalculator
import com.sletmoe.korogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Inventory
import com.sletmoe.korogue.components.Item
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.Named
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.perception.Blind
import com.sletmoe.korogue.perception.Darkvision
import com.sletmoe.korogue.perception.DarkvisionSense
import com.sletmoe.korogue.perception.Dazzled
import com.sletmoe.korogue.perception.Invisible
import com.sletmoe.korogue.perception.PerceptionModel
import com.sletmoe.korogue.perception.Sense
import com.sletmoe.korogue.perception.Sight
import com.sletmoe.korogue.perception.SightSense
import com.sletmoe.korogue.perception.StandardPerception
import com.sletmoe.korogue.perception.Telepathy
import com.sletmoe.korogue.perception.TelepathySense
import com.sletmoe.korogue.perception.Tremorsense
import com.sletmoe.korogue.perception.TremorsenseSense
import com.sletmoe.korogue.perception.TrueSight
import com.sletmoe.korogue.perception.TrueSightSense
import com.sletmoe.korogue.schedule.TimedEffect
import com.sletmoe.korogue.systems.BehaviorStrategy
import com.sletmoe.korogue.systems.HuntPlayerStrategy
import com.sletmoe.korogue.systems.WanderStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * The single seam describing a game's pluggable pieces to the engine (ADR-0009, hybrid
 * option iii). It bundles the per-concern registries — [strategies] (AI), [calculators]
 * (lighting), [senses] / [perceptionModels] (perception, ADR-0015), and [components]
 * (serialization) — so a consumer configures everything in one place
 * (`GameModule.engineDefaults().strategy(...).component<Foo>().build()`), while systems
 * and the save codec each depend only on the narrow registry they need, never the whole module.
 */
class GameModule private constructor(
    val strategies: Registry<BehaviorStrategy>,
    val calculators: Registry<LightValueCalculator>,
    val effects: Registry<TimedEffect>,
    val senses: Registry<Sense>,
    val perceptionModels: Registry<PerceptionModel>,
    val components: ComponentRegistry,
) {
    class Builder internal constructor(
        private val strategies: MutableMap<String, BehaviorStrategy>,
        private val calculators: MutableMap<String, LightValueCalculator>,
        private val effects: MutableMap<String, TimedEffect>,
        private val senses: MutableMap<String, Sense>,
        private val perceptionModels: MutableMap<String, PerceptionModel>,
        private val components: MutableMap<KClass<out Component>, KSerializer<out Component>>,
    ) {
        /** Register (or override) an AI strategy under [id]. */
        fun strategy(
            id: String,
            strategy: BehaviorStrategy,
        ): Builder = apply { strategies[id] = strategy }

        /** Register (or override) a scheduled [TimedEffect] (daemon/fuse) under [id]. */
        fun effect(
            id: String,
            effect: TimedEffect,
        ): Builder = apply { effects[id] = effect }

        /** Register (or override) a light calculator under [id]. */
        fun calculator(
            id: String,
            calculator: LightValueCalculator,
        ): Builder = apply { calculators[id] = calculator }

        /** Register (or override) a perception [Sense] contributor under [id] (ADR-0015). */
        fun sense(
            id: String,
            sense: Sense,
        ): Builder = apply { senses[id] = sense }

        /** Register (or override) a [PerceptionModel] under [id] (ADR-0015). Overrides the default [StandardPerception]. */
        fun perceptionModel(
            id: String,
            model: PerceptionModel,
        ): Builder = apply { perceptionModels[id] = model }

        /** Register a `@Serializable` [Component] type so it can be saved/loaded. */
        fun <T : Component> component(
            type: KClass<T>,
            serializer: KSerializer<T>,
        ): Builder = apply { components[type] = serializer }

        /** Register a `@Serializable` [Component] type by reified type. */
        inline fun <reified T : Component> component(): Builder = component(T::class, serializer<T>())

        fun build(): GameModule {
            val sensesRegistry = Registry(senses.toMap())
            // The default StandardPerception is wired to the finalised senses registry here, unless a
            // game has registered its own model under that id — breaking the model↔senses cycle.
            val models = perceptionModels.toMutableMap()
            models.putIfAbsent(StandardPerception.ID, StandardPerception(sensesRegistry))
            return GameModule(
                Registry(strategies.toMap()),
                Registry(calculators.toMap()),
                Registry(effects.toMap()),
                sensesRegistry,
                Registry(models.toMap()),
                ComponentRegistry(components.toMap()),
            )
        }
    }

    companion object {
        /** A builder pre-loaded with the engine's built-in strategies, calculators, and components. */
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
                // No built-in timed effects: the engine provides the scheduler; games provide effects.
                effects = mutableMapOf(),
                // The engine's built-in senses (ADR-0015). LOS senses are injected with the default
                // shadow caster (no LOS registry yet); the default perception model (StandardPerception)
                // is wired to this senses registry in build().
                senses =
                    mutableMapOf(
                        SightSense.ID to SightSense(SymmetricShadowCaster()),
                        DarkvisionSense.ID to DarkvisionSense(SymmetricShadowCaster()),
                        TrueSightSense.ID to TrueSightSense(SymmetricShadowCaster()),
                        TremorsenseSense.ID to TremorsenseSense(),
                        TelepathySense.ID to TelepathySense(),
                    ),
                perceptionModels = mutableMapOf(),
                components = mutableMapOf(),
            ).component<Position>()
                .component<ZoneMember>()
                .component<Named>()
                .component<Health>()
                .component<Renderable>()
                .component<Player>()
                .component<LightEmitter>()
                .component<Behavior>()
                .component<Portal>()
                .component<Item>()
                .component<Inventory>()
                .component<Sight>()
                .component<Darkvision>()
                .component<TrueSight>()
                .component<Tremorsense>()
                .component<Telepathy>()
                .component<Invisible>()
                .component<Blind>()
                .component<Dazzled>()
    }
}
