package com.sletmoe.krogue.components

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An entity's tile coordinate within its zone. Immutable; move by replacing it. */
@Serializable
@SerialName("position")
data class Position(val x: Int, val y: Int) : Component {
    val point: Vector2Int
        get() = Vector2Int(x, y)
}
