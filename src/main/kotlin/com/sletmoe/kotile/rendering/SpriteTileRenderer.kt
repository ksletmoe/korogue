package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet

/** Renders [StaticTile]s by drawing the matching cell from an image [TileSheet]. */
class SpriteTileRenderer(canvas: KotileCanvas, private val tileSheet: TileSheet) : TileRenderer(canvas) {
    override fun regionFor(staticTile: StaticTile): TextureRegion =
        tileSheet.region(staticTile.sheetX, staticTile.sheetY)
}
