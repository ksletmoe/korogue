package com.sletmoe.krogue.components

import com.sletmoe.krogue.algorithms.color.NormalizedRgb
import com.sletmoe.krogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which render layer an entity draws on, above the terrain layer (z=0). Higher
 * [zIndex] draws on top.
 */
@Serializable
enum class RenderLayer(val zIndex: Int) {
    CREATURE(1),
    PLAYER(2),
    OVERLAY(3),
}

/**
 * Presentation for an entity: the glyph and its foreground colour. Colour is a
 * [NormalizedRgb] (deeply immutable) rather than a mutable libGDX Color — the
 * renderer converts to a GDX Color at the draw boundary.
 */
@Serializable
@SerialName("renderable")
data class Renderable(
    val glyph: Char,
    val color: NormalizedRgb,
    val layer: RenderLayer = RenderLayer.CREATURE,
) : Component
