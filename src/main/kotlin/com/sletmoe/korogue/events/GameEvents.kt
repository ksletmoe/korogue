package com.sletmoe.korogue.events

import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.Event

/*
 * Concrete game events published on the `World.events` bus (Phase 4c). Each is an
 * immutable, self-contained snapshot: it carries the data observers need rather than an
 * id to look up, because by the time the bus dispatches (end of tick) the entity it
 * describes may already have changed or been despawned.
 *
 * These are emitted by gameplay systems and consumed by observers (a combat log, audio,
 * death handling); see the engine-side `ecs.EventBus`.
 */

/** A [target] took [amount] HP of damage (already floored at 0 health), dealt by [attacker]. */
data class EntityDamaged(
    val target: EntityId,
    val attacker: EntityId,
    val amount: Int,
    val remainingHealth: Int,
) : Event

/**
 * An [entity] reached 0 HP and was removed. Carries [name] (snapshotted before despawn)
 * so a log can name the deceased without resolving the now-gone entity.
 */
data class EntityDied(
    val entity: EntityId,
    val name: String?,
) : Event

/** An [entity] (currently only the player) moved from zone [from] to zone [to] via a portal. */
data class ZoneChanged(
    val entity: EntityId,
    val from: String,
    val to: String,
) : Event

/** The player picked up an item named [name] (the map entity is despawned, added to inventory). */
data class ItemPickedUp(
    val name: String,
) : Event
