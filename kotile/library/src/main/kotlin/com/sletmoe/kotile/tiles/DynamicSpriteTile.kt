package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * A [SpriteTile] whose appearance is a function of time: it owns its
 * [TextureRegion] frames and resolves the current one itself, rather than
 * delegating to the renderer the way [StaticSpriteTile] does.
 *
 * Frame-by-frame animation is the common case — see [AnimatedSpriteTile], the
 * built-in implementation — but any rule mapping elapsed time to a region works
 * (e.g. a tile that picks its region from live game state sampled at draw time).
 *
 * The time model is **stateless**: callers supply the elapsed wall-clock time in
 * milliseconds on every [regionFor] call. This keeps tile instances cheap and
 * shareable — one object can back many grid cells with no per-cell state — at
 * the cost of wall-clock syncing: every cell holding the same instance shows the
 * same frame at the same moment. For per-instance animation offsets (e.g. "this
 * cell started playing 200 ms later than that one"), wrap the tile and subtract
 * the desired offset from [elapsedMs] before delegating.
 *
 * The ASCII path's counterpart is
 * [com.sletmoe.kotile.display.ascii.DynamicAsciiTile].
 */
public interface DynamicSpriteTile : SpriteTile {
    /**
     * The color multiplied with this tile's pixels at draw time. [Color.WHITE]
     * leaves the sprite unchanged.
     */
    public val tint: Color

    /**
     * Returns the [TextureRegion] that should be drawn for this tile at the
     * given wall-clock time.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Passing `0` always returns the first (or only) frame.
     */
    public fun regionFor(elapsedMs: Long): TextureRegion

    /**
     * The color the renderer should actually multiply this tile's pixels by at
     * [elapsedMs] (krogue-2ur) — [tint] by default, so existing implementations
     * that don't override this keep their prior (constant-tint) behavior
     * unchanged. [AnimatedSpriteTile] overrides it to fold in a per-frame tint.
     */
    public fun tintFor(elapsedMs: Long): Color = tint

    /**
     * Mirror this tile horizontally/vertically when drawn (krogue-csc — e.g. a creature
     * sprite facing the direction it last moved). Defaulted so existing implementations
     * outside this module don't need to declare them.
     */
    public val flipX: Boolean get() = false
    public val flipY: Boolean get() = false
}
