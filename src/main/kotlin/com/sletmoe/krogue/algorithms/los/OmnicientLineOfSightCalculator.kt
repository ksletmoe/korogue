package com.sletmoe.krogue.algorithms.los

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.Tile

class OmnicientLineOfSightCalculator : LineOfSightCalculator {
    override fun calculateLineOfSight(
        origin: Vector2Int,
        tiles: Grid<Tile>,
        maxViewDistance: Double?,
    ): Grid<Boolean> {
        return Grid(tiles.width, tiles.height, true)
    }
}
