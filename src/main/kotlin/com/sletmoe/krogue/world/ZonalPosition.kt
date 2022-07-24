package com.sletmoe.krogue.world

import java.awt.Point

class ZonalPosition(var zone: Zone, x: Int, y: Int) {
    fun copy(): ZonalPosition = ZonalPosition(zone, x, y)

    var point = Point(x, y)
    var x: Int
        get() = point.x
        set(value) {
            point.x = value
        }

    var y: Int
        get() = point.y
        set(value) {
            point.y = value
        }
}
