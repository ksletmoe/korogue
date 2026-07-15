package com.sletmoe.kotile.display.ascii

/**
 * An [AsciiTile] whose appearance is a function of time: [resolveAt] may return
 * a different [StaticAsciiTile] as the clock advances.
 *
 * Frame-by-frame animation is the common case — see [AnimatedAsciiTile], the
 * built-in implementation — but any rule mapping elapsed time to an appearance
 * works. Implement this interface (rather than [AsciiTile], which is sealed) to
 * supply your own: a cell that pulses in step with a game-state value, say, or
 * one whose glyph is chosen by a procedural noise function.
 *
 * The time model is **stateless**: callers supply the elapsed wall-clock time on
 * every [resolveAt] call, so one instance can back many grid cells with no
 * per-cell state. Implementations are expected to be pure with respect to
 * [elapsedMs] — the window may call [resolveAt] any number of times per frame,
 * and caches results per cell (ADR-0024).
 *
 * The sprite path's counterpart is
 * [com.sletmoe.kotile.tiles.DynamicSpriteTile].
 */
public interface DynamicAsciiTile : AsciiTile
