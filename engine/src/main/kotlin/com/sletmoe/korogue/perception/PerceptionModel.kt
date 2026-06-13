package com.sletmoe.korogue.perception

import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.world.GameWorld

/**
 * Perception's policy seam (ADR-0015, layer 3): turns the world facts (illumination, line-of-sight)
 * plus an observer's senses and targets' concealment into a [Perceived] result. The primitive is a
 * **query** — callable for the player, a companion, a charmed monster, or a monster asking "can I see
 * the player?" — so the renderer, AI, and faction visibility all reuse one model.
 *
 * Resolved by id through the `GameModule` perception-model registry (ADR-0009), so a game can replace
 * the whole policy — e.g. a `RoomBasedPerception` for Rogue's lit/dark rooms (krogue-kj5) — without
 * touching the engine. The default is [StandardPerception].
 */
fun interface PerceptionModel {
    /** What [observer] perceives in [world] right now. Pure: must not mutate the world. */
    fun perceive(
        observer: Entity,
        world: GameWorld,
    ): Perceived
}
