package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.PipelineStage
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.pipeline.StandardStage
import com.sletmoe.korogue.registry.Registry

/**
 * Drives a [Scheduler] from the ECS turn loop (krogue-6uq): each [World.tick] it fires every timer
 * due this turn. Register it like any other [System]; its position in the pipeline sets when timed
 * effects run relative to AI/movement/combat. Both built-in consumers register it first so
 * hunger/regen apply at the top of the turn, but [StandardStage.SCHEDULE] carries no ordering
 * constraint — that's a convention, not a data dependency, so the engine doesn't enforce it.
 *
 * [effects] is the `GameModule` registry a timer's effect id resolves through, mirroring how
 * [com.sletmoe.korogue.systems.BehaviorSystem] takes the strategy registry.
 */
class SchedulerSystem(
    private val scheduler: Scheduler,
    private val effects: Registry<TimedEffect>,
) : Staged {
    override val stage: PipelineStage get() = StandardStage.SCHEDULE

    override fun update(
        world: World,
        ctx: TickContext,
    ) = scheduler.advance(world, ctx, effects::resolve)
}
