package com.sletmoe.krogue.algorithms.zonegen

import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.Tile
import kotlin.random.Random

typealias ZoneFeatureGenerator = (Grid<Tile>, Random) -> Vector2Int
