package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A [TileRenderer] that resolves each [StaticTile] to the matching cell of an
 * image [TileSheet]. This is the entry point for rendering arbitrary sprite
 * sheets; for text grids see
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow].
 *
 * [com.sletmoe.kotile.tiles.AnimatedSpriteTile] tiles placed via
 * [drawTile][TileRenderer.drawTile] resolve their own [TextureRegion] frames
 * and do not consult the [tileSheet]; the sheet is only used for [StaticTile]
 * lookup.
 *
 * @param canvas the canvas tiles are drawn to
 * @param tileSheet the sheet that backs every [StaticTile]'s `(sheetX, sheetY)`
 */
public class SpriteTileRenderer(canvas: KotileCanvas, private val tileSheet: TileSheet) : TileRenderer(canvas) {
    override fun regionFor(staticTile: StaticTile): TextureRegion =
        tileSheet.region(staticTile.sheetX, staticTile.sheetY)
}
