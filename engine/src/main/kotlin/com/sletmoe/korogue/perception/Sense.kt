package com.sletmoe.korogue.perception

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * An opt-in data **component** declaring that its owner has a sense (ADR-0015): `Sight`,
 * `Darkvision`, `Tremorsense`, a game's own `HeatSense`, …. The component carries the sense's
 * per-entity data (e.g. a sight radius) and names the [senseId] of the [Sense] contributor that
 * interprets it, resolved through the `GameModule` senses registry — mirroring `LightEmitter`'s
 * `calculatorId` and `Behavior`'s strategy id (ADR-0003: components are data, behaviour resolves by
 * id). No component, no sense: a creature with only `Tremorsense` never triggers sight, so per-entity
 * opt-out is automatic. The engine's built-in sense components arrive with the senses themselves
 * (krogue-1my.2).
 */
interface SenseComponent : Component {
    /** The id of the [Sense] contributor that interprets this component. */
    val senseId: String
}

/**
 * The behaviour behind a [SenseComponent] (ADR-0015, ADR-0009 registry style): given an observer and
 * its sense component, it [reveal]s the cells and entities that sense exposes. Registered by id on
 * the `GameModule` (`module.sense(id, impl)`); [StandardPerception] resolves the id from each of the
 * observer's [SenseComponent]s and unions the contributions in its reveal phase.
 *
 * Selective interaction is by **tags**, never a sense×effect matrix: a sense declares the [tags] it
 * *is* or *depends on* (`sight` → `{visual, light-dependent}`), and [pierces] the concealment or
 * suppressor tags it sees through (see-invisible is a visual sense that pierces `Invisible`'s tag).
 * Concealments and suppressors target tags; piercing is the inverse capability. Tags are plain string
 * ids (engine constants land with the built-in senses; there is deliberately no tag registry).
 *
 * Contract (mirrors [com.sletmoe.korogue.systems.BehaviorStrategy]):
 * - **Pure.** [reveal] reads the world and returns a [Contribution]; it must not mutate anything.
 * - **Stateless / shared.** One instance is registered once and reused for every observer bearing its
 *   id, so keep per-entity data in the [SenseComponent], not the contributor.
 */
interface Sense {
    /** What this sense *is* / depends on. Concealments and suppressors negate by matching these. */
    val tags: Set<String>

    /** Concealment/suppressor tags this sense sees through despite a match (e.g. see-invisible). */
    val pierces: Set<String>
        get() = emptySet()

    /** The cells and entities this sense exposes for [observer], given its [sense] component data. */
    fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution
}

/**
 * One sense's output for one observer: the [cells] it exposes and the [entities] it makes the
 * observer aware of. [StandardPerception] tags each contribution with its producing [Sense]'s
 * [Sense.tags]/[Sense.pierces] when applying the suppress phase, so a contributor only states *what*
 * it reveals, not *how* it interacts with concealment.
 */
data class Contribution(
    val cells: Set<Vector2Int> = emptySet(),
    val entities: Set<EntityId> = emptySet(),
) {
    companion object {
        val EMPTY = Contribution()
    }
}
