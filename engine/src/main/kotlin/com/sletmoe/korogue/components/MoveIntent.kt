package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component

/**
 * A pending request to move by ([dx], [dy]) this tick. Movement is data: player input
 * and AI attach a `MoveIntent`; `MovementSystem` resolves and consumes it (a step onto
 * walkable terrain, a bump-attack on an occupant, or a no-op into a wall).
 */
data class MoveIntent(
    val dx: Int,
    val dy: Int,
) : Component
