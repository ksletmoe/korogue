package com.sletmoe.korogue.events

import com.sletmoe.korogue.algorithms.color.NormalizedRgb
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.Event
import com.sletmoe.kotile.utilities.Vector2Int

/*
 * Concrete game events published on the `World.events` bus (Phase 4c). Each is an
 * immutable, self-contained snapshot: it carries the data observers need rather than an
 * id to look up, because by the time the bus dispatches (end of tick) the entity it
 * describes may already have changed or been despawned.
 *
 * These are emitted by gameplay systems and consumed by observers (a combat log, audio,
 * death handling); see the engine-side `ecs.EventBus`.
 */

/**
 * A [target] took [amount] HP of damage (already floored at 0 health), dealt by [attacker].
 * Carries [position] (the target's cell at the moment of the hit, snapshotted since the target
 * may despawn — or move — before this dispatches) so a presentation observer (e.g. the
 * event-animation queue, krogue-wuq) can place a hit-flash without resolving the entity.
 */
data class EntityDamaged(
    val target: EntityId,
    val attacker: EntityId,
    val amount: Int,
    val remainingHealth: Int,
    val position: Vector2Int? = null,
) : Event

/**
 * An [entity] reached 0 HP and was removed. Carries [name], [glyph], and [color] (all
 * snapshotted before despawn) so a log can name the deceased, and a presentation observer
 * (krogue-wuq) can play a death animation at [position], without resolving the now-gone entity.
 */
data class EntityDied(
    val entity: EntityId,
    val name: String?,
    val position: Vector2Int? = null,
    val glyph: Char? = null,
    val color: NormalizedRgb? = null,
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

/**
 * [attacker] fired a ranged attack at [target] (krogue-4tn), landing as an
 * [com.sletmoe.korogue.components.AttackIntent] `CombatSystem` resolves like any other. [from]/[to]
 * are the shooter's and target's cells at the moment of firing; [path] is the clear line between
 * them (`RangedAttackSystem` already validated it against terrain that blocks line of sight) — a
 * presentation observer can hand it straight to
 * [com.sletmoe.korogue.presentation.VisualEvent.GlyphProjectile.path] without recomputing it.
 */
data class RangedAttackFired(
    val attacker: EntityId,
    val target: EntityId,
    val from: Vector2Int,
    val to: Vector2Int,
    val path: List<Vector2Int>,
) : Event
