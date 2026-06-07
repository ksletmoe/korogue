package com.sletmoe.krogue.world

import com.badlogic.gdx.graphics.Color

open class Tile(
    val name: String,
    val glyph: Char,
    val color: Color,
    val backgroundColor: Color,
    val isWalkable: Boolean,
    val blocksLineOfSight: Boolean,
    val description: String? = null,
)

val BLANK_TILE =
    Tile(
        "The Void",
        ' ',
        Color.WHITE,
        Color.BLACK,
        isWalkable = true,
        blocksLineOfSight = false,
    )
