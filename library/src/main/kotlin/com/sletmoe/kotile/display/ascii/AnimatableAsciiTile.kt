package com.sletmoe.kotile.display.ascii

/**
 * Marker interface for any ASCII cell content that can be stored in an
 * [AsciiTileWindow] grid.
 *
 * Two concrete branches:
 * - [AsciiTileDescriptor] — a fixed glyph + foreground/background color triple.
 * - [AnimatedAsciiTile] — cycles through a sequence of [AsciiTileDescriptor]
 *   frames over time (e.g. Brogue-style lighting flicker).
 */
public sealed interface AnimatableAsciiTile {
    /**
     * Returns the [AsciiTileDescriptor] that should be drawn for this cell at
     * the given wall-clock time.
     *
     * For a static [AsciiTileDescriptor] this always returns `this`. For an
     * [AnimatedAsciiTile] it returns the frame active at [elapsedMs].
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Passing `0` always returns the first (or only) frame.
     */
    public fun descriptorAt(elapsedMs: Long): AsciiTileDescriptor
}
