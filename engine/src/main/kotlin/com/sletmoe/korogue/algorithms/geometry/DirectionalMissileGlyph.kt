package com.sletmoe.korogue.algorithms.geometry

/**
 * The traditional roguelike missile character for travel direction ([dx], [dy]) — the ASCII
 * counterpart to [com.sletmoe.kotile.rendering.rotationTowards]'s continuous sprite rotation
 * (krogue-m05): a glyph can't rotate geometrically, so instead of one continuous angle this buckets
 * the direction into whichever of four characters best represents it, defaulting to the classic
 * `- | \ /` set. A caller building a [com.sletmoe.korogue.presentation.VisualEvent.GlyphProjectile]
 * picks its own `glyph` however it likes; this is just the common, reusable bucketing so most games
 * don't need to reinvent it — the specific characters are parameters, not hardcoded, since the exact
 * choice (and any recreation of a particular game's own convention) is a caller/game concern.
 *
 * A purely vertical or horizontal delta is unambiguous; a diagonal delta whose components share sign
 * (both positive or both negative — travelling toward the bottom-right or top-left) uses
 * [diagonalDown] (`\`), the opposite-sign diagonal uses [diagonalUp] (`/`). ([dx], [dy] == 0, 0)
 * falls back to [horizontal] — there is no direction to bucket, so any single choice is arbitrary.
 */
fun directionalMissileGlyph(
    dx: Int,
    dy: Int,
    horizontal: Char = '-',
    vertical: Char = '|',
    diagonalDown: Char = '\\',
    diagonalUp: Char = '/',
): Char =
    when {
        dx == 0 && dy == 0 -> horizontal
        dx == 0 -> vertical
        dy == 0 -> horizontal
        (dx > 0) == (dy > 0) -> diagonalDown
        else -> diagonalUp
    }
