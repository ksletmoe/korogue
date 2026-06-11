package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Drives an entity's AI. [strategyId] names a behavior resolved via the `GameModule` strategy registry
 * (e.g. wander, hunt-player); `BehaviorSystem` runs it each tick to emit a [MoveIntent].
 * Stored by stable id rather than a strategy object so the component stays serializable
 * (the registry convention from ARCHITECTURE.md; general registry arrives with 4f).
 */
@Serializable
@SerialName("behavior")
data class Behavior(
    val strategyId: String,
) : Component
