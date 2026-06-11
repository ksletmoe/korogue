package com.sletmoe.korogue.algorithms.zonegen

import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.random.Random

typealias ZoneFeatureGenerator = (Grid<Tile>, Random) -> Vector2Int
