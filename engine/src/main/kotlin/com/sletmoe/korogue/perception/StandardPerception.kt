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
 *    contribution whose sense [Sense.tags] they negate. Confining all precedence here keeps the reveal
 *    phase simple.
 *
 * **Piercing defeats *concealment*, never *suppression*** (ADR-0015). A [Sense.pierces]d tag lets a
 * sense see a target that conceals from it (see-invisible / true-sight), but a [Suppressor] that
 * negates one of the sense's tags drops it unconditionally — a suppressor disables the *channel* the
 * sense runs on, and you can't pierce your own blinded eyes. Immunity to a suppressor therefore comes
 * from being on a channel it doesn't reach (a non-`{visual}` sense survives `Blind`), not from
 * piercing. A game wanting pierce-everything-including-suppression swaps this [PerceptionModel]
 * (ADR-0014) for one whose suppress check honours `pierces`.
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

        // Phase 2a — observer suppressors gate whole senses (and thus their cells). Suppression is
        // absolute on its channel: pierces does NOT apply, so a blinded {visual} sense is always dropped.
        val suppressedTags = observer.components.filterIsInstance<Suppressor>().flatMapTo(HashSet()) { it.negatesTags }
        val surviving = revealed.filterNot { (sense, _) -> sense.isSuppressedBy(suppressedTags) }

        val cells = surviving.flatMapTo(HashSet()) { it.second.cells }

        // Phase 2b — per-target concealment gates entities, per surviving sense. Here pierces DOES
        // apply: a sense that pierces the concealment's tag sees the target anyway (see-invisible).
        val entities = HashSet<EntityId>()
        for ((sense, contribution) in surviving) {
            for (id in contribution.entities) {
                if (id in entities) continue
                val target = world.ecs.get(id) ?: continue
                val concealTags =
                    target.components.filterIsInstance<Concealment>().flatMapTo(HashSet()) { it.concealsFromTags }
                if (!sense.isConcealedBy(concealTags)) entities += id
            }
        }

        return Perceived(zoneId, cells, entities)
    }

    /** True if a suppressor negates a tag this sense *is* — dropped regardless of [Sense.pierces]. */
    private fun Sense.isSuppressedBy(negated: Set<String>): Boolean = negated.isNotEmpty() && tags.any { it in negated }

    /** True if a concealment negates a tag this sense *is* and does not [Sense.pierces] — i.e. it can't see the target. */
    private fun Sense.isConcealedBy(negated: Set<String>): Boolean =
        negated.isNotEmpty() && tags.any { it in negated && it !in pierces }

    companion object {
        const val ID = "standard"
    }
}
