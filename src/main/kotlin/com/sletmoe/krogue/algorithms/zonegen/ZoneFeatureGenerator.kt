package com.sletmoe.krogue.algorithms.zonegen

import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.world.Tile
import java.awt.Point
import kotlin.random.Random

typealias ZoneFeatureGenerator = (Grid<Tile>, Random) -> Point
