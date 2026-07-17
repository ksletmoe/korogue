package com.sletmoe.korogue.ecs

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
 * A named stage a [System] may occupy, and the stages that must already have run when it does
 * (krogue-32d). The ECS core defines the *mechanism* only — it knows nothing about which stages
 * exist; a game (or the engine's own `pipeline.StandardStage`) supplies the vocabulary and the
 * constraints between them.
 *
 * Ordering is a **partial** order, deliberately: only real data dependencies belong in [runsAfter].
 * Two stages with no path between them may be registered in either order, and the engine's own
 * consumers rely on that — the demo picks up items before resolving combat while the Rogue port
 * does the reverse, and both are correct because pickup and combat never touch the same state.
 * Encoding a constraint that isn't a genuine dependency would reject a legitimate pipeline.
 *
 * Tagging a stage rather than naming a class is what lets a game *replace* a built-in and keep its
 * ordering guarantees: the Rogue port's `RogueCombatSystem` stands in for `CombatSystem`, declares
 * the same [StandardStage.COMBAT], and is checked against the same constraints.
 */
interface PipelineStage {
    /** Stable identifier, used in violation messages. */
    val id: String

    /** Stages that must be registered *before* any system in this stage. Empty = unconstrained. */
    val runsAfter: Set<PipelineStage>
        get() = emptySet()
}

/**
 * A [System] that declares the [stage] it occupies, opting in to registration-order validation
 * (see [World.validateSystemOrder]). Systems that don't implement this are unconstrained and never
 * cause a violation — [System] stays a `fun interface`, so a lambda system remains legal.
 */
interface Staged : System {
    val stage: PipelineStage
}

/**
 * Thrown when the registered systems violate a [PipelineStage.runsAfter] constraint — i.e. a
 * pipeline that would produce silently wrong results (stale visibility, dropped intents) rather
 * than an obvious crash. Raised by [World.validateSystemOrder], which [World.tick] calls itself.
 */
class PipelineOrderException(
    message: String,
) : IllegalStateException(message)

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
