package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.utilities.Direction
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * A pending request to move by ([dx], [dy]) this tick. Movement is data: player input
 * and AI attach a `MoveIntent`; `MovementSystem` resolves and consumes it (a step onto
 * walkable terrain, a bump-attack on an occupant, or a no-op into a wall).
 */
data class MoveIntent(
    val dx: Int,
    val dy: Int,
) : Component {
    /** A one-cell move in [direction] — the common case for input and AI. */
    constructor(direction: Direction) : this(direction.dx, direction.dy)

    /** A move by [delta], the canonical coordinate form — e.g. `MoveIntent(target - origin)` (ADR-0034). */
    constructor(delta: Vector2Int) : this(delta.x, delta.y)

    /** This intent's displacement as a [Vector2Int]. */
    val delta: Vector2Int
        get() = Vector2Int(dx, dy)
}
