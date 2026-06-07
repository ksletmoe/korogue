package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component

/**
 * Drives an entity's AI. [strategyId] names a behavior resolved via `BehaviorStrategies`
 * (e.g. wander, hunt-player); `BehaviorSystem` runs it each tick to emit a [MoveIntent].
 * Stored by stable id rather than a strategy object so the component stays serializable
 * (the registry convention from ARCHITECTURE.md; general registry arrives with 4f).
 */
data class Behavior(
    val strategyId: String,
) : Component
