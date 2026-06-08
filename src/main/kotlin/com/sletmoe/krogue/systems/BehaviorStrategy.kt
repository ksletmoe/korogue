package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.MoveIntent
import com.sletmoe.krogue.ecs.Entity
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World

/**
 * The AI extension point (Phase 4d): one entity's decision for the current tick. A
 * consuming game implements this, registers the instance under a stable id on its
 * `GameModule` (`module.strategy(id, impl)`), and tags entities with `Behavior(id)`;
 * each tick `BehaviorSystem` resolves the id and calls [decide]. The built-in
 * strategies ([WanderStrategy], [HuntPlayerStrategy]) live in `BehaviorStrategies.kt`.
 *
 * Contract:
 * - **Pure decision.** [decide] reads the [world] and returns an intent; it must not
 *   mutate the world. `BehaviorSystem` applies the returned [MoveIntent] (the single
 *   mutation seam, ADR-0005), so the world stays consistent across the tick.
 * - **Return null to pass.** A null result means "stay put this tick" — no intent is
 *   attached. The decision surface is currently movement; richer action types (attack,
 *   use, cast) would broaden this return type, not the registration mechanism.
 * - **Deterministic via [ctx].** Draw any randomness from `ctx.random` (a seeded
 *   gameplay stream, ADR-0009) so the same seed replays identically — never `Random.Default`.
 * - **Stateless / shared.** One instance is registered once and reused for every entity
 *   bearing its id, so keep per-entity state in components, not in the strategy object.
 *
 * @see Behavior the component that names a strategy by id.
 * @see BehaviorSystem the system that resolves the id and runs the strategy.
 */
fun interface BehaviorStrategy {
    fun decide(
        world: World,
        self: Entity,
        ctx: TickContext,
    ): MoveIntent?
}
