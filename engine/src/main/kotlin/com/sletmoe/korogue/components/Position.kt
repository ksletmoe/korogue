package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.kotile.utilities.Vector2Int
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An entity's tile coordinate within its zone. Immutable; move by replacing it. */
@Serializable
@SerialName("position")
data class Position(val x: Int, val y: Int) : Component {
    /** Construct from a [Vector2Int] — the canonical coordinate form (ADR-0034). */
    constructor(point: Vector2Int) : this(point.x, point.y)

    val point: Vector2Int
        get() = Vector2Int(x, y)
}
