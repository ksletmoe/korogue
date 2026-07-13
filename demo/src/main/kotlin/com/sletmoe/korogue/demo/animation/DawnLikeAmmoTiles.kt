package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.tiles.StaticTile

/**
 * Named DawnLike projectile sprites from `Items/Ammo.png` (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase's free-form (sprite-side)
 * projectile (krogue-aqo).
 *
 * The source art is drawn at a fixed diagonal (fletching at the bottom-left, head at the
 * top-right) — rotating it to point along an arbitrary flight direction needs kotile rotation
 * support that doesn't exist yet ([krogue-m05]); until that lands this can only fly along its
 * native northeast/southwest diagonal.
 */
object DawnLikeAmmoTiles {
    /** Arrow: blue-tipped head, fixed northeast-pointing diagonal. */
    val ARROW = StaticTile(sheetX = 0, sheetY = 2)
}
