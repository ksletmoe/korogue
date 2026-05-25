package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color

data class AsciiTileDescriptor(
    val character: Char,
    val foregroundColor: Color = Color.WHITE,
    val backgroundColor: Color = Color.BLACK,
)
