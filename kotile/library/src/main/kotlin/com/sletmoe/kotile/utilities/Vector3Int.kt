package com.sletmoe.kotile.utilities

/**
 * An integer grid position with a stacking layer.
 *
 * @property x column, increasing rightwards
 * @property y row, increasing downwards
 * @property z layer index; higher layers are drawn on top
 */
data class Vector3Int(val x: Int, val y: Int, val z: Int)
