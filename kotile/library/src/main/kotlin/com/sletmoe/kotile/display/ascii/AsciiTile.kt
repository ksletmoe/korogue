package com.sletmoe.kotile.display.ascii

/**
 * Anything that can be stored as cell content in an [AsciiTileWindow] grid.
 *
 * Two branches, split by whether the cell's appearance depends on time:
 * - [StaticAsciiTile] — a fixed glyph + foreground/background color triple;
 *   the common case, and also the *resolved* form every cell reduces to.
 * - [DynamicAsciiTile] — resolves to a different [StaticAsciiTile] as time
 *   passes. [AnimatedAsciiTile] is the built-in implementation (e.g.
 *   Brogue-style lighting flicker).
 *
 * This mirrors the sprite path's [com.sletmoe.kotile.tiles.SpriteTile]
 * hierarchy one-for-one — same base/static/dynamic/animated roles, same names —
 * so intuition transfers between the two. The one structural difference is
 * dispatch: an ASCII cell resolves itself via [resolveAt], whereas the sprite
 * path's renderer resolves [com.sletmoe.kotile.tiles.StaticSpriteTile]
 * coordinates against a sheet.
 */
public sealed interface AsciiTile {
    /**
     * Returns the [StaticAsciiTile] that should be drawn for this cell at the
     * given wall-clock time — this cell's appearance with time factored out.
     *
     * A [StaticAsciiTile] always returns `this`; a [DynamicAsciiTile] returns
     * whichever appearance is active at [elapsedMs].
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Passing `0` always returns the first (or only) frame.
     */
    public fun resolveAt(elapsedMs: Long): StaticAsciiTile
}
