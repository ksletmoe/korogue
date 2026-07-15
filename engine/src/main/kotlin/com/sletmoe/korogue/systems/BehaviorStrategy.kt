package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World

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
 * - **Deterministic via [ctx].** Draw any randomness from `ctx.random` — the world's seeded
 *   gameplay stream (ADR-0009, ADR-0025) — so the same seed replays identically and a save
 *   resumes mid-stream. Reaching for `Random.Default` (or any RNG of your own) instead is the
 *   one way a strategy can still break that guarantee: it is unseeded and unsaveable, so the
 *   replay diverges. Need an isolated sequence? Take a named stream off `world.random`.
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
