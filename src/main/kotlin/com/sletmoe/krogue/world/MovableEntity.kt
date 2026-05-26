package com.sletmoe.krogue.world

import java.awt.Color

abstract class MovableEntity(
    var position: ZonalPosition,
    name: String,
    glyph: Char,
    color: Color,
    description: String? = null,
) : Entity(name, glyph, color, description) {
    open fun moveInZone(
        dx: Int,
        dy: Int,
    ) {
        position.x += dx
        position.y += dy
    }

    open fun moveToZone(zonalPosition: ZonalPosition) {
        position = zonalPosition.copy()
    }
}
