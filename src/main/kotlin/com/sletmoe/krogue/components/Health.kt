package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Hit points. Immutable: damage/heal by replacing via World.update. */
@Serializable
@SerialName("health")
data class Health(val current: Int, val max: Int) : Component {
    val alive: Boolean
        get() = current > 0
    val dead: Boolean
        get() = !alive
}
