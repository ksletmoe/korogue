package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Opts an entity into `RangedAttackSystem` (krogue-4tn): once per tick, if this entity has a clear
 * line of sight to the player within [range] tiles (Chebyshev) and further than melee range (an
 * adjacent target is left to the ordinary bump-attack path), it fires instead of moving that tick.
 *
 * Deliberately player-only-as-target and AI-only-as-attacker for now — there is no player-initiated
 * ranged attack yet (needs its own target-selection input), and no monster-vs-monster ranged combat.
 */
@Serializable
@SerialName("ranged-attacker")
data class RangedAttacker(
    val range: Int = DEFAULT_RANGE,
) : Component {
    companion object {
        const val DEFAULT_RANGE = 6
    }
}
