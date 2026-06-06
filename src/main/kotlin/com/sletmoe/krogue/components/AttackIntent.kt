package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component
import com.sletmoe.krogue.ecs.EntityId

/**
 * A pending melee attack on [targetId], emitted by `MovementSystem` when a [MoveIntent]
 * is blocked by an occupied cell and resolved by `CombatSystem`. The attacker is the
 * entity that holds the component.
 */
data class AttackIntent(
    val targetId: EntityId,
) : Component
