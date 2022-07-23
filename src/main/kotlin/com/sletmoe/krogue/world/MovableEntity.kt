package com.sletmoe.krogue.world

import java.awt.Color
import java.awt.Point

abstract class MovableEntity(
    var position: Point, name: String, glyph: Char, color: Color, description: String? = null
) : Entity(name, glyph, color, description) {
    fun move(dx: Int, dy: Int) {
        position.x += dx
        position.y += dy
    }
}
