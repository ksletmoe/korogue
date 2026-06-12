package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import kotlinx.serialization.Serializable

/**
 * One scheduled timer: a [TimedEffect] (by registered [effectId]) due to fire on turn [fireOnTurn].
 * [period] distinguishes the two kinds Rogue uses — `0` is a one-shot **fuse** (fires once, then
 * gone); `> 0` is a recurring **daemon** (after firing it reschedules for `currentTurn + period`).
 * [handle] is the stable id the [Scheduler] returns so callers can [Scheduler.cancel] or
 * [Scheduler.lengthen] the timer later. Pure data, so the whole schedule round-trips through saves.
 */
@Serializable
data class ScheduledEffect(
    val handle: Long,
    val effectId: String,
    val fireOnTurn: Long,
    val period: Long,
)

/** The [Scheduler]'s persistable state: its pending timers plus the counters needed to resume. */
@Serializable
data class SchedulerState(
    val currentTurn: Long = 0L,
    val nextHandle: Long = 0L,
    val effects: List<ScheduledEffect> = emptyList(),
)

/**
 * A general turn-keyed scheduler — the engine's daemon/fuse mechanism (krogue-6uq, modelled on
 * Rogue's `daemons.c`). Schedule **fuses** (one-shot, fire after N turns) and **daemons**
 * (recurring, fire every N turns); [cancel] or [lengthen] them by [Handle]; [advance] once per world
 * turn to fire everything due. Effects are referenced by registered id (see [TimedEffect]) so the
 * schedule is fully serializable ([snapshot] / [restore]) and timers resume after a save/load.
 *
 * Drive it from the ECS turn loop via [SchedulerSystem]; pairs with the turn-on-input loop
 * (krogue-lhw), where one player action is one world turn. Not thread-safe (single-threaded turn
 * loop). Firing order within a turn is by [Handle] (i.e. scheduling order), and an effect may freely
 * schedule, cancel, or lengthen timers while firing — changes take effect from the next due check.
 */
class Scheduler {
    // Keyed by handle; LinkedHashMap keeps scheduling order, which is also handle order.
    private val effects = LinkedHashMap<Long, ScheduledEffect>()
    private var nextHandle = 0L

    /** The most recent turn [advance] processed; the base for relative scheduling. */
    var currentTurn: Long = 0L
        private set

    /** Opaque id for a scheduled timer, returned by [fuse]/[daemon] and accepted by [cancel]/[lengthen]. */
    @JvmInline
    value class Handle(
        val value: Long,
    )

    /**
     * Schedule [effectId] to fire **once**, [afterTurns] turns from now (the current turn).
     * `afterTurns = 1` means "next turn". Returns a [Handle] for later [cancel]/[lengthen].
     */
    fun fuse(
        effectId: String,
        afterTurns: Long,
    ): Handle {
        require(afterTurns >= 1) { "afterTurns must be >= 1, was $afterTurns" }
        return schedule(effectId, fireOnTurn = currentTurn + afterTurns, period = 0L)
    }

    /**
     * Schedule [effectId] to fire **every** [everyTurns] turns, starting [everyTurns] turns from now.
     * `everyTurns = 1` fires every turn. Returns a [Handle] for later [cancel]/[lengthen].
     */
    fun daemon(
        effectId: String,
        everyTurns: Long,
    ): Handle {
        require(everyTurns >= 1) { "everyTurns must be >= 1, was $everyTurns" }
        return schedule(effectId, fireOnTurn = currentTurn + everyTurns, period = everyTurns)
    }

    private fun schedule(
        effectId: String,
        fireOnTurn: Long,
        period: Long,
    ): Handle {
        val handle = nextHandle++
        effects[handle] = ScheduledEffect(handle, effectId, fireOnTurn, period)
        return Handle(handle)
    }

    /** Cancel the timer [handle]; returns true if it existed (a no-op otherwise). */
    fun cancel(handle: Handle): Boolean = effects.remove(handle.value) != null

    /**
     * Shift the timer [handle]'s next firing by [byTurns] (negative hastens it). Returns true if it
     * existed. The shift compounds with a daemon's [period] only for the *next* firing — subsequent
     * firings resume the original period.
     */
    fun lengthen(
        handle: Handle,
        byTurns: Long,
    ): Boolean {
        val entry = effects[handle.value] ?: return false
        effects[handle.value] = entry.copy(fireOnTurn = entry.fireOnTurn + byTurns)
        return true
    }

    /** True if [handle] is still scheduled. */
    fun isScheduled(handle: Handle): Boolean = effects.containsKey(handle.value)

    /** Number of pending timers. */
    val size: Int get() = effects.size

    /**
     * Fire every timer due on or before [TickContext.turn], in scheduling order; remove fired fuses
     * and reschedule fired daemons. Call once per world turn (see [SchedulerSystem]). Effects may
     * mutate the schedule as they run: each is re-read just before firing, so a timer cancelled or
     * pushed into the future by an earlier effect this turn won't fire.
     */
    fun advance(
        world: World,
        ctx: TickContext,
        resolve: (String) -> TimedEffect,
    ) {
        currentTurn = ctx.turn
        // Snapshot candidates so effects can mutate `effects` while we iterate.
        val due = effects.values.filter { it.fireOnTurn <= ctx.turn }.sortedBy { it.handle }
        for (candidate in due) {
            val entry = effects[candidate.handle] ?: continue // cancelled by an earlier effect
            if (entry.fireOnTurn > ctx.turn) continue // lengthened past now by an earlier effect
            resolve(entry.effectId).apply(world, this, ctx)
            rescheduleOrRemove(entry.handle, ctx.turn)
        }
    }

    // After firing, advance a daemon to its next period or drop a spent fuse — but only if the
    // effect itself didn't already reschedule/lengthen this handle while running.
    private fun rescheduleOrRemove(
        handle: Long,
        firedOnTurn: Long,
    ) {
        val after = effects[handle] ?: return // effect cancelled itself
        if (after.fireOnTurn > firedOnTurn) return // effect rescheduled itself into the future
        if (after.period > 0) {
            effects[handle] = after.copy(fireOnTurn = currentTurn + after.period)
        } else {
            effects.remove(handle)
        }
    }

    /** Capture the full schedule for saving. */
    fun snapshot(): SchedulerState = SchedulerState(currentTurn, nextHandle, effects.values.toList())

    /** Replace the schedule with persisted [state] (used by the save codec on load). */
    fun restore(state: SchedulerState) {
        effects.clear()
        for (entry in state.effects) effects[entry.handle] = entry
        nextHandle = state.nextHandle
        currentTurn = state.currentTurn
    }
}
