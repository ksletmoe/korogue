package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.utilities.Vector2Int

/**
 * The wall-mounted torch's two DawnLike animation frames (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase's light-flicker demo
 * (krogue-aqo, feeding krogue-ncl/krogue-2ur).
 *
 * Unlike [DawnLikeWallTiles]/[DawnLikeFloorTiles] this isn't a single [com.sletmoe.kotile.tiles.StaticSpriteTile]:
 * DawnLike ships 2-frame decor animation as two *separate* sheet files sharing the same cell
 * coordinate, rather than two cells within one sheet. Building the actual
 * [com.sletmoe.kotile.tiles.AnimatedSpriteTile] needs both `Objects/Decor0.png` and
 * `Objects/Decor1.png` loaded as separate `TileSheet`s, each contributing one
 * `AnimationFrame` region from [CELL].
 *
 * Despite the file order, `Decor1.png` holds the brighter, bigger flame (taller, more of the
 * pale cream highlight) and `Decor0.png` the smaller, dimmer one — confirmed by inspecting the
 * actual pixels at [CELL] in both files, not by file-name convention. Cycling between the two on
 * a short interval is the flicker.
 */
object DawnLikeTorchTile {
    /** (sheetX, sheetY) of the torch cell — identical in both Decor0.png and Decor1.png. */
    val CELL = Vector2Int(0, 8)
}
