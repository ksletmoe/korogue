package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.kotile.utilities.Vector2Int
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A zone-transition marker. When a (player) entity stands on this cell, it is sent to
 * ([targetX], [targetY]) in zone [targetZoneId]; resolved by `PortalSystem`. Portals are
 * non-blocking — you step *onto* them — so `MovementSystem` ignores them for occupancy.
 */
@Serializable
@SerialName("portal")
data class Portal(
    val targetZoneId: String,
    val targetX: Int,
    val targetY: Int,
) : Component {
    /** Construct from a [Vector2Int] target — the canonical coordinate form (ADR-0034). */
    constructor(targetZoneId: String, target: Vector2Int) : this(targetZoneId, target.x, target.y)

    /** The destination cell as a [Vector2Int]. */
    val target: Vector2Int
        get() = Vector2Int(targetX, targetY)
}
