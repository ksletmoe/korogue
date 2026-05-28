package com.sletmoe.krogue.world

import java.awt.Color

open class Tile(
    name: String,
    glyph: Char,
    color: Color,
    val backgroundColor: Color,
    val isWalkable: Boolean,
    val blocksLineOfSight: Boolean,
    description: String? = null,
) : Entity(name, glyph, color, description)

val BLANK_TILE =
    Tile(
        "The Void",
        ' ',
        Color.white,
        Color.black,
        isWalkable = true,
        blocksLineOfSight = false,
    )
