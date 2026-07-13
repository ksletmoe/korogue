package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.tiles.StaticTile

/**
 * Named DawnLike wall pieces from `Objects/Wall.png` (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase demo's sprite (left) half
 * of a split room (krogue-aqo). Coordinates are (sheetX, sheetY) in tile-grid units —
 * hand-picked against the labeled grid `demo/scripts/label-tilesheet.py` renders
 * (`./gradlew :demo:fetchDawnlikeAssets` first).
 *
 * No right-wall or right-corner pieces: the room is split down the middle between this sprite
 * half and a glyph (ASCII) half, so that boundary is a rendering seam, not an actual wall — the
 * sprite half only ever needs its own left edge, plus top/bottom runs.
 */
object DawnLikeWallTiles {
    /** West wall turning the corner into the north wall (top-left of the sprite half). */
    val UPPER_LEFT_CORNER = StaticTile(sheetX = 0, sheetY = 3)

    /** A plain north-wall segment (repeat horizontally to span the sprite half's width). */
    val TOP_WALL = StaticTile(sheetX = 1, sheetY = 3)

    /** A plain west-wall segment (repeat vertically for a wall of any height). */
    val LEFT_WALL = StaticTile(sheetX = 0, sheetY = 4)

    /** West wall turning the corner into the south wall (bottom-left of the sprite half). */
    val BOTTOM_LEFT_CORNER = StaticTile(sheetX = 0, sheetY = 5)

    /** A plain south-wall segment (repeat horizontally to span the sprite half's width). */
    val BOTTOM_WALL = StaticTile(sheetX = 4, sheetY = 5)
}
