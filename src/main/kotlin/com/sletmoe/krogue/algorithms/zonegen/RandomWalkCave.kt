package com.sletmoe.krogue.algorithms.zonegen

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.world.Tile

fun randomWalkCave(
    startX: Int,
    startY: Int,
    length: Int,
    groundTile: Tile,
): ZoneFeatureGenerator {
    return { tiles, random ->
        var direction: Int
        var x = startX
        var y = startY

        repeat(length) {
            direction = random.nextInt(4)
            if (direction == 0 && x + 1 < tiles.lastColumnIndex) {
                x += 1
            } else if (direction == 1 && x - 1 > 0) {
                x -= 1
            } else if (direction == 2 && y + 1 < tiles.lastRowIndex) {
                y += 1
            } else if (direction == 3 && y - 1 > 0) {
                y -= 1
            }
            tiles[x, y] = groundTile
        }

        Vector2Int(x, y)
    }
}
