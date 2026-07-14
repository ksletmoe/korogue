package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.ecs.EntityId

/**
 * A pending attack on [targetId], resolved by `CombatSystem` into damage regardless of how it got
 * here — distance/reach is entirely the emitter's concern. `MovementSystem` emits it when a
 * [MoveIntent] is blocked by an occupied cell (melee bump); `RangedAttackSystem` (krogue-4tn) emits
 * it directly for a distant target with a clear line of sight. The attacker is the entity that
 * holds the component.
 */
data class AttackIntent(
    val targetId: EntityId,
) : Component
