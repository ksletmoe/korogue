package com.sletmoe.korogue.components

import com.sletmoe.korogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which way an entity's sprite should face when drawn (krogue-csc): left or right, set by
 * `MovementSystem` from the horizontal component of the entity's last resolved [MoveIntent]
 * and persisting across ticks — an entity that stops moving keeps facing the way it last
 * moved, rather than resetting. Deliberately two-way, not a full compass: this exists to
 * drive sprite mirroring ([com.sletmoe.kotile.display.KotileCanvas.drawSprite]'s `flipX`),
 * not aiming or field-of-view, which already have [com.sletmoe.korogue.utilities.Direction].
 *
 * A move with zero horizontal delta (a pure vertical step) leaves the current `Facing`
 * unchanged rather than picking an arbitrary side.
 */
@Serializable
@SerialName("facing")
enum class Facing : Component {
    LEFT,
    RIGHT,
}
