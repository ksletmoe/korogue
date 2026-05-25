package com.sletmoe.kotile.tiles

import javafx.scene.paint.Color

data class StaticTile(
    val sheetX: Int,
    val sheetY: Int,
    val tint: Color = Color.WHITE,
)
