package com.sletmoe.korogue.algorithms.los

import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int

class OmnicientLineOfSightCalculator : LineOfSightCalculator {
    override fun calculateLineOfSight(
        origin: Vector2Int,
        tiles: Grid<Tile>,
        maxViewDistance: Double?,
    ): Grid<Boolean> {
        return Grid(tiles.width, tiles.height, true)
    }
}
