package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.tiles.StaticTile

/**
 * Named DawnLike projectile sprites from `Items/Ammo.png` (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase's free-form (sprite-side)
 * projectile (krogue-aqo).
 *
 * The source art is drawn at a fixed diagonal — despite how it reads at a glance, the blue end is
 * the *fletching* (tail) and the pale cream/gray end is the *head* (point), confirmed by direct
 * pixel inspection and by which orientation actually reads correctly once rotated toward a flight
 * direction (krogue-m05/krogue-2ua) — not by file-name or color convention.
 */
object DawnLikeAmmoTiles {
    /** Arrow: pale cream/gray head at the bottom-left, blue fletching at the top-right (southwest-facing native bearing). */
    val ARROW = StaticTile(sheetX = 0, sheetY = 2)
}
