package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How an entity moves: the set of locomotion [modes][MovementTags] it can travel
 * by. `MovementSystem` uses it to decide whether an occupant (or, later, terrain)
 * blocks a step — a mover passes an obstacle as long as it has one mode the
 * obstacle's [Collision.blocks] set does not stop.
 *
 * An entity with **no** `Locomotion` is treated as a plain walker (`{walk}`), so
 * ordinary ground creatures need not carry it. Games tag flyers/swimmers/etc.
 * with the relevant modes (`Locomotion(setOf(MovementTags.FLY))`).
 *
 * @property modes the locomotion tags this entity can use; empty means it cannot
 *   move under its own power (always blocked by any obstacle).
 */
@Serializable
@SerialName("locomotion")
data class Locomotion(
    val modes: Set<String> = setOf(MovementTags.WALK),
) : Component
