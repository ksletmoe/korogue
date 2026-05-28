package com.sletmoe.krogue.world

import com.badlogic.gdx.graphics.Color
import java.awt.Point

class Pointer(position: ZonalPosition, color: Color) : HighlightedCoordinate(position, color) {
    fun moveInZone(
        dx: Int,
        dy: Int,
    ) {
        val destination = Point(position.x + dx, position.y + dy)
        if (position.zone.bounds.contains(destination)) {
            position.point = destination
        }
    }
}
