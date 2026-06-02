package com.sletmoe.krogue.world

import com.sletmoe.kotile.utilities.Vector2Int

class ZonalPosition(var zone: Zone, x: Int, y: Int) {
    fun copy(): ZonalPosition = ZonalPosition(zone, x, y)

    var point = Vector2Int(x, y)
    var x: Int
        get() = point.x
        set(value) {
            point = Vector2Int(value, point.y)
        }

    var y: Int
        get() = point.y
        set(value) {
            point = Vector2Int(point.x, value)
        }
}
