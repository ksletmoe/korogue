package com.sletmoe.korogue.perception

import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.registry.Registry
import com.sletmoe.korogue.world.GameWorld

/**
 * The engine's default [PerceptionModel] (ADR-0015): the **two-phase reveal/suppress** model over the
 * observer's composable senses.
 *
 * 1. **Reveal.** For each [SenseComponent] the observer carries, resolve its [Sense] from [senses] and
 *    union the cells and entities it exposes. Phase one is order-independent — it is a pure union.
 * 2. **Suppress.** Apply the observer's [Suppressor]s and each target's [Concealment]s, which drop a
 *    contribution whose sense [Sense.tags] they negate — unless that sense [Sense.pierces] the matched
 *    tag. Confining all precedence here keeps the reveal phase simple.
 *
 * Cells come only from senses that survive the observer's suppressors (concealment is a property of
 * *targets*, so it gates entities, not terrain). An entity is perceived if **any** surviving sense
 * exposes it without being concealed from that sense — two senses compose, so a tremor-sensed
 * invisible creature is still perceived.
 *
 * The model ships **inert**: with no senses registered (krogue-1my.2) and no concrete suppressors or
 * concealments (krogue-1my.3), an observer perceives nothing and nothing is suppressed. A basic game
 * adds a `Sight` component and the built-in senses do the rest.
 */
class StandardPerception(
    private val senses: Registry<Sense>,
) : PerceptionModel {
    override fun perceive(
        observer: Entity,
        world: GameWorld,
    ): Perceived {
        val zoneId = observer.get<ZoneMember>()?.zoneId ?: return Perceived()

        // Phase 1 — reveal: pair each of the observer's senses with what it exposes.
        val revealed =
            observer.components
                .filterIsInstance<SenseComponent>()
                .map { component ->
                    val sense = senses.resolve(component.senseId)
                    sense to sense.reveal(observer, component, world)
                }

        // Phase 2a — observer suppressors gate whole senses (and thus their cells).
        val suppressedTags = observer.components.filterIsInstance<Suppressor>().flatMapTo(HashSet()) { it.negatesTags }
        val surviving = revealed.filterNot { (sense, _) -> sense.isNegatedBy(suppressedTags) }

        val cells = surviving.flatMapTo(HashSet()) { it.second.cells }

        // Phase 2b — per-target concealment gates entities, per surviving sense.
        val entities = HashSet<EntityId>()
        for ((sense, contribution) in surviving) {
            for (id in contribution.entities) {
                if (id in entities) continue
                val target = world.ecs.get(id) ?: continue
                val concealTags =
                    target.components.filterIsInstance<Concealment>().flatMapTo(HashSet()) { it.concealsFromTags }
                if (!sense.isNegatedBy(concealTags)) entities += id
            }
        }

        return Perceived(zoneId, cells, entities)
    }

    /** True if [negated] contains a tag this sense *is* and does not pierce — i.e. this sense is dropped. */
    private fun Sense.isNegatedBy(negated: Set<String>): Boolean =
        negated.isNotEmpty() && tags.any { it in negated && it !in pierces }

    companion object {
        const val ID = "standard"
    }
}
