package com.sletmoe.krogue.world

import asciiPanel.AsciiCharacterData
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

val BLANK_CHARACTER = AsciiCharacterData(' ', Color.white, Color.black)
val BLANK_TILE =
    Tile(
        "The Void",
        BLANK_CHARACTER.character,
        BLANK_CHARACTER.foregroundColor,
        BLANK_CHARACTER.backgroundColor,
        isWalkable = true,
        blocksLineOfSight = false,
    )
