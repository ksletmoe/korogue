package com.sletmoe.korogue.perception

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * What an observer perceives right now (ADR-0015, layer 3): the set of [cells] it can see and the
 * set of [entities] it is aware of, both relative to the observer's current [zoneId]. Produced by a
 * [PerceptionModel]; consumed by the renderer (which observer's eyes to draw) and by AI ("can I
 * perceive the player?").
 *
 * Doubles as a **derived cache component**: [PerceptionSystem] writes a fresh `Perceived` each tick
 * onto every observer that carries one, exactly as `LightingSystem` rewrites `Zone.lightMap`. It is
 * recomputed, never persisted — it is deliberately *not* registered on the `GameModule`, so the save
 * codec skips it (like a transient intent). To opt an entity into per-tick caching, attach an empty
 * `Perceived`; querying [PerceptionModel.perceive] directly stays available for observers sampled
 * less often than every tick.
 */
data class Perceived(
    val zoneId: String = "",
    val cells: Set<Vector2Int> = emptySet(),
    val entities: Set<EntityId> = emptySet(),
) : Component {
    /** True if the cell at ([x], [y]) is currently perceived. */
    fun sees(
        x: Int,
        y: Int,
    ): Boolean = Vector2Int(x, y) in cells

    /** True if [entity] is currently perceived. */
    fun sees(entity: EntityId): Boolean = entity in entities
}
