package com.sletmoe.korogue.world

import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
open class Tile(
    val name: String,
    val glyph: Char,
    @Serializable(with = GdxColorSerializer::class) val color: Color,
    @Serializable(with = GdxColorSerializer::class) val backgroundColor: Color,
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
