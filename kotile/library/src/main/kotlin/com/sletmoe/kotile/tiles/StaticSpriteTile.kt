package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color

/**
 * A tile drawn from a [TileSheet] cell.
 *
 * Region resolution is delegated to the renderer
 * ([com.sletmoe.kotile.rendering.SpriteTileRenderer]); the tile carries only
 * the sheet coordinates and tint. For a tile that owns its
 * [com.badlogic.gdx.graphics.g2d.TextureRegion] frames directly (required for
 * animation) see [AnimatedSpriteTile].
 *
 * @property sheetX column of the tile within the sheet
 * @property sheetY row of the tile within the sheet
 * @property tint color multiplied with the sprite's pixels at draw time; the
 *   default [Color.WHITE] leaves the sprite unchanged (no color manipulation)
 * @property flipX mirror the tile horizontally when drawn (krogue-csc)
 * @property flipY mirror the tile vertically when drawn (krogue-csc)
 */
public data class StaticSpriteTile(
    val sheetX: Int,
    val sheetY: Int,
    val tint: Color = Color.WHITE,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
) : SpriteTile
