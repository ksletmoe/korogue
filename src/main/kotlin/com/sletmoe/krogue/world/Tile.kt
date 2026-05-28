package com.sletmoe.krogue.world

import com.badlogic.gdx.graphics.Color

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
        Color.WHITE,
        Color.BLACK,
        isWalkable = true,
        blocksLineOfSight = false,
    )
