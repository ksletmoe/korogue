package com.sletmoe.krogue.ecs

import kotlin.random.Random

/**
 * A unit of behaviour executed once per [World.tick]. Systems are plain classes:
 * constructor-inject dependencies (calculators, config, RNG) for testability.
 * Execution order is the order systems are registered via [World.addSystem].
 *
 * Declared as a `fun interface` so trivial systems can be written as lambdas while
 * stateful ones remain classes.
 */
fun interface System {
    fun update(
        world: World,
        ctx: TickContext,
    )
}

/**
 * Ambient inputs handed to every [System] each tick. Adding fields here extends
 * what systems can observe without changing the [System.update] signature.
 *
 * @property turn the turn currently being processed (0-based); [World.currentTurn]
 *   reaches N after N completed ticks.
 * @property elapsedMs wall-clock milliseconds since the previous tick (0 when unused).
 * @property random RNG source for systems that need stochastic behavior.
 */
data class TickContext(
    val turn: Long,
    val elapsedMs: Long,
    val random: Random,
)
