package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-entity occupancy: whether a positioned entity obstructs movement into its
 * cell, and what a blocked mover does about it (krogue-x9q). This replaces
 * `MovementSystem`'s former hardcoded Portal/Item denylist with a data-driven
 * signal (ADR-0003: components are data, behaviour resolves), so any game can
 * make an arbitrary positioned entity (trap, secret door, altar, decoration)
 * walkable-onto or solid without the engine naming its type.
 *
 * Two orthogonal axes:
 * - [blocks] — which [MovementTags] this occupant stops. A mover is blocked iff
 *   *all* of its [Locomotion] modes are in this set (`moverModes ⊆ blocks`); it
 *   passes if it has one mode this set omits (a flyer over a `{walk}`-only
 *   blocker). An empty set is **passable to everyone** — you step *onto* it
 *   (portals, floor items, traps).
 * - [bump] — what happens when a mover *is* blocked: attack it, or treat it as a
 *   solid no-op.
 *
 * **Absence of `Collision` means solid + attackable** (`PHYSICAL`, [BumpResponse.ATTACK]),
 * i.e. today's default for a creature — fail-closed, so a monster you forget to
 * tag still blocks and can be bumped-to-attack (the safe direction).
 *
 * @property blocks locomotion modes this occupant obstructs; empty = passable.
 * @property bump the response when a mover is blocked by this occupant.
 */
@Serializable
@SerialName("collision")
data class Collision(
    val blocks: Set<String> = MovementTags.PHYSICAL,
    val bump: BumpResponse = BumpResponse.ATTACK,
) : Component {
    companion object {
        /**
         * A positioned entity you step *onto*, not into — portals, floor items,
         * traps, decorations. Blocks nothing, so [bump] never applies.
         */
        val PASSABLE: Collision = Collision(blocks = emptySet())
    }
}

/**
 * What a mover does when a [Collision] blocks its step.
 *
 * `ATTACK` emits an `AttackIntent` at the blocker (the creature default);
 * `BLOCK` is a solid no-op (boulder, statue, closed portcullis). Further
 * responses (e.g. `INTERACT` for doors/levers) can be added later without
 * touching the movement resolution shape.
 */
@Serializable
enum class BumpResponse {
    ATTACK,
    BLOCK,
}
