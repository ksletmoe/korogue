package com.sletmoe.krogue

import java.awt.Color

open class Entity(
    val name: String,
    val glyph: Char,
    val color: Color,
    val description: String? = null,
)

open class Character(
    x: Int,
    y: Int,
    name: String,
    glyph: Char,
    color: Color,
    description: String? = null
) : Entity(name, glyph, color, description) {
    private var _x = x
    val x: Int
        get() = _x
    private var _y = y
    val y: Int
        get() = _y

    fun move(dx: Int, dy: Int) {
        _x += dx
        _y += dy
    }
}

open class Tile(
    name: String,
    glyph: Char,
    color: Color,
    val backgroundColor: Color,
    val isBlocked: Boolean,
    description: String? = null,
) : Entity(name, glyph, color, description)

val BLANK_TILE = Tile("empty space", ' ', color = Color.black, backgroundColor = Color.black, isBlocked = false)

class TileGrid(width: Int, height: Int, defaultTile: Tile = BLANK_TILE) {
    val width: Int
        get() = tiles.size
    val height: Int
        get() = tiles.size

    val lastColumnIndex: Int = width - 1
    val lastRowIndex: Int = height - 1

    private val tiles = MutableList(width) {
        MutableList(height) {
            defaultTile
        }
    }

    fun getTile(x: Int, y: Int): Tile {
        checkBounds(x, y)
        return tiles[x][y]
    }

    fun setTile(x: Int, y: Int, tile: Tile) {
        checkBounds(x, y)
        tiles[x][y] = tile
    }

    operator fun get(x: Int, y: Int): Tile = getTile(x, y)
    operator fun set(x: Int, y: Int, tile: Tile) = setTile(x, y, tile)

    private fun checkBounds(x: Int, y: Int) {
        if (x < 0 || x > tiles.lastIndex) {
            throw RuntimeException("($x, $y) is not within Grid column bounds: 0 - ${tiles.lastIndex}")
        } else if (y < 0 || y > tiles[x].lastIndex) {
            throw RuntimeException("($x, $y) is not within Grid row bounds: 0 - ${tiles[x].lastIndex}")
        }
    }
}


