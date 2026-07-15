package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.tiles.StaticSpriteTile

/**
 * Named DawnLike floor pieces from `Objects/Floor.png` (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase demo's sprite (left) half
 * of a split room (krogue-aqo). Coordinates are (sheetX, sheetY) in tile-grid units.
 *
 * Floor uses the same edge-shading autotile idea as [DawnLikeWallTiles]: each color has a 3x3
 * block where the top row carries a highlighted top-adjacent-to-wall strip, the left column
 * carries a highlighted left-adjacent-to-wall strip, and the two combine at the corner —
 * verified against both this (gray) and the cyan block at (0,3), which share the same layout.
 * No right-edge/right-corner pieces, for the same reason [DawnLikeWallTiles] has none: the
 * sprite half's right boundary is the glyph-rendering seam, not a wall.
 */
object DawnLikeFloorTiles {
    /** Floor touching both the top and left walls. */
    val TOP_LEFT_CORNER = StaticSpriteTile(sheetX = 0, sheetY = 6)

    /** Floor touching the top wall only (repeat horizontally along the top run). */
    val TOP_EDGE = StaticSpriteTile(sheetX = 1, sheetY = 6)

    /** Floor touching the left wall only (repeat vertically along the left run). */
    val LEFT_EDGE = StaticSpriteTile(sheetX = 0, sheetY = 7)

    /** Interior floor touching no wall. */
    val MIDDLE = StaticSpriteTile(sheetX = 1, sheetY = 7)

    /** Floor touching both the bottom and left walls. */
    val BOTTOM_LEFT_CORNER = StaticSpriteTile(sheetX = 0, sheetY = 8)

    /** Floor touching the bottom wall only (repeat horizontally along the bottom run). */
    val BOTTOM_EDGE = StaticSpriteTile(sheetX = 1, sheetY = 8)
}
