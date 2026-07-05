package com.sletmoe.korogue.world

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.MovementTags
import kotlinx.serialization.Serializable

/**
 * A terrain cell. Passability is per-locomotion-mode via [blocks] (krogue-xeb,
 * ADR-0022): the modes this cell stops, resolved by the same set relation as
 * entity [com.sletmoe.korogue.components.Collision] — a mover passes iff it has a
 * [MovementTags] mode this set omits (a flyer over water/lava).
 *
 * [isWalkable] is the walk axis and stays the source most terrain declares; by
 * default [blocks] is derived from it (`∅` for walkable, [MovementTags.PHYSICAL]
 * for a solid wall), so walk-only worlds need no changes. A game wanting
 * mode-selective terrain sets [blocks] explicitly and keeps [isWalkable]
 * consistent (`WALK ∈ blocks ⇔ !isWalkable`) — e.g. water: `isWalkable = false,
 * blocks = setOf(WALK)`; lava a flyer clears: `blocks = setOf(WALK, SWIM)`.
 */
@Serializable
open class Tile(
    val name: String,
    val glyph: Char,
    @Serializable(with = GdxColorSerializer::class) val color: Color,
    @Serializable(with = GdxColorSerializer::class) val backgroundColor: Color,
    val isWalkable: Boolean,
    val blocksLineOfSight: Boolean,
    val description: String? = null,
    val blocks: Set<String> = if (isWalkable) emptySet() else MovementTags.PHYSICAL,
) {
    init {
        require(isWalkable == (MovementTags.WALK !in blocks)) {
            "Tile '$name' is inconsistent: isWalkable=$isWalkable but blocks=$blocks " +
                "(WALK must be in blocks iff the tile is not walkable)."
        }
    }
}

val BLANK_TILE =
    Tile(
        "The Void",
        ' ',
        Color.WHITE,
        Color.BLACK,
        isWalkable = true,
        blocksLineOfSight = false,
    )
