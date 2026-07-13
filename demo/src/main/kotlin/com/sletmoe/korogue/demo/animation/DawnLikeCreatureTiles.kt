package com.sletmoe.korogue.demo.animation

import com.sletmoe.kotile.utilities.Vector2Int

/**
 * Named DawnLike creature sprites (16x16 tiles, CC-BY 4.0 — see
 * `demo/assets/dawnlike/ATTRIBUTION.md`), for the animation showcase's two combatants
 * (krogue-aqo).
 *
 * Same two-file animation shape as [DawnLikeTorchTile]: a creature's two frames live in
 * separate sheet files sharing one cell coordinate ([Sheet.frame0]/[Sheet.frame1]) and
 * alternate for an idle bounce-in-place animation, rather than two cells within one sheet.
 * Different creatures draw from different sheet pairs, so each entry pairs a [Sheet] with
 * its cell.
 */
object DawnLikeCreatureTiles {
    /** Which `Characters/` file pair a creature's two animation frames come from. */
    enum class Sheet(val frame0Path: String, val frame1Path: String) {
        PLAYER("Characters/Player0.png", "Characters/Player1.png"),
        PEST("Characters/Pest0.png", "Characters/Pest1.png"),
    }

    /** Green-armored ranger/rogue, orange helmet ([Sheet.PLAYER]). */
    val RANGER = Sheet.PLAYER to Vector2Int(2, 3)

    /** Coiled scorpion, claws and curled tail ([Sheet.PEST]). */
    val SCORPION = Sheet.PEST to Vector2Int(5, 2)
}
