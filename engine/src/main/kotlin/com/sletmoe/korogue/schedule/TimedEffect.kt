package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World

/**
 * A turn-keyed effect run by the [Scheduler] (krogue-6uq) — the engine's analogue of Rogue's
 * daemon/fuse callbacks (regen, hunger, status-effect expiry, wandering-monster spawns, …). The
 * engine provides the *scheduling mechanism*; a consuming game provides the effects and registers
 * each under a string id (via `GameModule.effect(...)`), exactly like a [com.sletmoe.korogue
 * .systems.BehaviorStrategy]. Referencing effects by id is what lets the schedule be saved: only
 * the id and turn counters are persisted, never the (unserializable) code.
 *
 * An effect receives the [scheduler] so it can schedule, cancel, or lengthen other timers (and its
 * own follow-ups) — Rogue daemons routinely start and kill one another.
 */
fun interface TimedEffect {
    /**
     * Runs this effect during the world turn [ctx].turn. A one-shot fuse fires once and is then
     * removed; a recurring daemon fires and is rescheduled for its next period (see [Scheduler]).
     */
    fun apply(
        world: World,
        scheduler: Scheduler,
        ctx: TickContext,
    )
}
