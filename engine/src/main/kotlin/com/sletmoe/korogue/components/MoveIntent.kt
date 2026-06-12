package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.utilities.Direction

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
}
