package com.sletmoe.korogue.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The engine's built-in SenseComponents (ADR-0015). Each sense is its **own concrete component type**
 * because the ECS keys components by class (one per type per entity), and each is **opt-in**: a sense
 * contributes nothing unless the observer carries its component, so a mindless ooze with only
 * Tremorsense simply never triggers sight. The component is pure data — it names the `senseId` of the
 * Sense contributor that interprets it (defaulting to the engine's, overridable to point at a game's)
 * and carries this observer's per-entity, mutable sense data (its `radius`).
 *
 * `radius` is `null` by default = *unbounded by distance*: for the line-of-sight senses (Sight,
 * Darkvision) that means limited only by walls (and, for sight, lighting) — the view-distance that
 * used to live on `MapPanel` (krogue-f50) now lives here, per-observer and mutable, so a curse can
 * shrink it for N turns and the scheduler restore it. For the radial senses (Tremorsense,
 * Telepathy) `null` means the whole zone.
 *
 * All four are registered for save/load in `GameModule.engineDefaults()`, so an entity's senses persist
 * across a save (a cursed-down `Sight.radius` survives) — unlike the derived, transient Perceived.
 */

/** Sight: the `{visual, light-dependent}` sense — line-of-sight gated on lighting, capped at [radius]. */
@Serializable
@SerialName("sight")
data class Sight(
    val radius: Double? = null,
    override val senseId: String = SightSense.ID,
) : SenseComponent

/** Darkvision: a `{visual}` sense that sees by line-of-sight to [radius] regardless of lighting. */
@Serializable
@SerialName("darkvision")
data class Darkvision(
    val radius: Double? = null,
    override val senseId: String = DarkvisionSense.ID,
) : SenseComponent

/**
 * Tremorsense: a `{vibration}` sense that feels living creatures within [radius] through the ground —
 * no line-of-sight, no lighting; it reveals the creatures, not the terrain.
 */
@Serializable
@SerialName("tremorsense")
data class Tremorsense(
    val radius: Double? = null,
    override val senseId: String = TremorsenseSense.ID,
) : SenseComponent

/**
 * Telepathy: a `{mental}` sense that perceives living minds within [radius] (the whole zone when
 * `null`) regardless of line-of-sight, lighting, or walls — the "detect monsters" staple. Reveals the
 * creatures, not the terrain.
 */
@Serializable
@SerialName("telepathy")
data class Telepathy(
    val radius: Double? = null,
    override val senseId: String = TelepathySense.ID,
) : SenseComponent
