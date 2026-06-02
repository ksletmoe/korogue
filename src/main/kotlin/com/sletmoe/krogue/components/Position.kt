package com.sletmoe.krogue.components

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.ecs.Component

/** An entity's tile coordinate within its zone. Immutable; move by replacing it. */
data class Position(val x: Int, val y: Int) : Component {
    val point: Vector2Int
        get() = Vector2Int(x, y)
}
