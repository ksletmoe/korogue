package com.sletmoe.krogue.components

import com.sletmoe.krogue.ecs.Component

/** Hit points. Immutable: damage/heal by replacing via World.update. */
data class Health(val current: Int, val max: Int) : Component {
    val alive: Boolean
        get() = current > 0
    val dead: Boolean
        get() = !alive
}
