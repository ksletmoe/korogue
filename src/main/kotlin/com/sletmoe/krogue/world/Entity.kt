package com.sletmoe.krogue.world

import com.badlogic.gdx.graphics.Color

abstract class Entity(
    val name: String,
    val glyph: Char,
    val color: Color,
    val description: String? = null,
)
