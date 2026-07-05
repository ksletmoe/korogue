package com.sletmoe.korogue.components

/**
 * The engine's built-in **locomotion tag ids** (krogue-x9q; mirrors
 * [com.sletmoe.korogue.perception.PerceptionTags]). A mover declares the modes
 * it can travel by ([Locomotion.modes]); an obstacle declares the modes it stops
 * ([Collision.blocks]). Movement is resolved by a set relation rather than a
 * mover×obstacle matrix: a mover is **blocked** iff *every* mode it can use is
 * blocked (`moverModes ⊆ blocks`), i.e. it passes as long as it has one mode the
 * obstacle does not stop.
 *
 * Like perception tags these are deliberately plain string ids with **no
 * registry**: a tag carries no behaviour to resolve. A game declares its own
 * modes the same way — as constants (e.g. `PHASE`, `BURROW`, `CLIMB`) — and can
 * target the engine's by referencing these. Adding a mode never touches the
 * engine.
 */
object MovementTags {
    /** Ordinary ground movement. The default for a mover with no [Locomotion]. */
    const val WALK = "walk"

    /** Movement over the ground/water/lava — an obstacle blocking only [WALK] is passed by a flyer. */
    const val FLY = "fly"

    /** Movement through water — an obstacle blocking only [WALK] is passed by a swimmer. */
    const val SWIM = "swim"

    /**
     * The physical modes a solid obstacle stops by default: `{walk, fly, swim}`.
     * A game mode outside this set (e.g. a `PHASE` ghost) is *not* stopped by a
     * default-solid obstacle, so it passes through — the extensibility payoff of
     * resolving by set relation instead of a boolean.
     */
    val PHYSICAL: Set<String> = setOf(WALK, FLY, SWIM)
}
