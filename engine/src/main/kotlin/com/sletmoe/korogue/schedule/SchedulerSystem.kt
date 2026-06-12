package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World

/**
 * Drives a [Scheduler] from the ECS turn loop (krogue-6uq): each [World.tick] it fires every timer
 * due this turn. Register it like any other [System]; its position in the pipeline sets when timed
 * effects run relative to AI/movement/combat (e.g. register first so hunger/regen apply at the top
 * of the turn). Resolve effect ids through a `GameModule`'s effects registry
 * (`gameModule.effects::resolve`), mirroring how [com.sletmoe.korogue.systems.BehaviorSystem]
 * resolves strategies.
 */
class SchedulerSystem(
    private val scheduler: Scheduler,
    private val resolve: (String) -> TimedEffect,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) = scheduler.advance(world, ctx, resolve)
}
