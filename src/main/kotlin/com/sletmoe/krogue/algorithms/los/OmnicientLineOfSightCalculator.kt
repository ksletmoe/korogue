package com.sletmoe.krogue.algorithms.los

import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.Tile
import java.awt.Point

class OmnicientLineOfSightCalculator : LineOfSightCalculator {
    override fun calculateLineOfSight(
        origin: Point,
        tiles: Grid<Tile>,
        maxViewDistance: Double?,
    ): Grid<Boolean> {
        return Grid(tiles.width, tiles.height, true)
    }
}
