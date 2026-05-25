package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color

/**
 * A tile drawn from a [TileSheet] cell at ([sheetX], [sheetY]).
 *
 * [tint] is multiplied with the sprite's pixels at draw time; the default
 * [Color.WHITE] leaves the sprite unchanged (no color manipulation).
 */
data class StaticTile(
    val sheetX: Int,
    val sheetY: Int,
    val tint: Color = Color.WHITE,
)
