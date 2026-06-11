package com.sletmoe.korogue.components

import com.sletmoe.korogue.algorithms.color.NormalizedRgb
import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An entity that casts light: a [color] and [radius] (in tiles), plus a [calculatorId]
 * naming the falloff model resolved via the `GameModule` calculator registry. The calculator is referenced
 * by stable id rather than stored, so the component stays serializable (the registry
 * convention from ARCHITECTURE.md; the general registry arrives with save/load in 4f).
 */
@Serializable
@SerialName("light-emitter")
data class LightEmitter(
    val color: NormalizedRgb,
    val radius: Double,
    val calculatorId: String,
) : Component
