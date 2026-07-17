package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A [TileRenderer] that resolves each [StaticSpriteTile] to the matching cell of an
 * image [TileSheet]. This is the entry point for rendering arbitrary sprite
 * sheets; for text grids see
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow].
 *
 * [com.sletmoe.kotile.tiles.AnimatedSpriteTile] tiles placed via
 * [drawTile][TileRenderer.drawTile] resolve their own [TextureRegion] frames
 * and do not consult the [tileSheet]; the sheet is only used for [StaticSpriteTile]
 * lookup.
 *
 * @param canvas the canvas tiles are drawn to
 * @param tileSheet the sheet that backs every [StaticSpriteTile]'s `(sheetX, sheetY)`
 */
public class SpriteTileRenderer(
    canvas: KotileCanvas,
    private val tileSheet: TileSheet,
    sharesCanvas: Boolean = false,
) : TileRenderer(canvas, sharesCanvas) {
    override fun regionFor(staticTile: StaticSpriteTile): TextureRegion =
        tileSheet.region(staticTile.sheetX, staticTile.sheetY)
}
