package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * A [DynamicSpriteTile] that cycles through a sequence of [TextureRegion] frames over time.
 *
 * Time model is **stateless**: callers supply the elapsed wall-clock time on
 * every [regionFor] call. The same instance may be placed at multiple grid
 * cells; all cells show the same frame at the same wall-clock time. To display
 * independent animation phases at different cells, create separate instances
 * or subtract a per-cell offset from [elapsedMs] before calling [regionFor].
 *
 * ## Construction example
 * ```kotlin
 * val tile = AnimatedSpriteTile(
 *     frames = listOf(
 *         AnimationFrame(sheet.region(0, 0), durationMs = 100),
 *         AnimationFrame(sheet.region(1, 0), durationMs = 100),
 *         AnimationFrame(sheet.region(2, 0), durationMs = 100),
 *     ),
 *     mode = PlaybackMode.LOOP,
 * )
 * ```
 *
 * @property frames ordered list of frames. Must not be empty; every frame's
 *   [AnimationFrame.durationMs] must be positive.
 * @property mode how the animation behaves once it reaches the end of the
 *   sequence; defaults to [PlaybackMode.LOOP].
 * @property tint color multiplied with the drawn pixels at render time.
 *   [Color.WHITE] (the default) leaves the sprite unchanged. Composes with any
 *   per-frame [AnimationFrame.tint] — see [tintFor].
 * @property flipX mirror the tile horizontally when drawn (krogue-csc)
 * @property flipY mirror the tile vertically when drawn (krogue-csc)
 * @throws IllegalArgumentException if [frames] is empty or any frame has a
 *   non-positive [AnimationFrame.durationMs].
 */
public class AnimatedSpriteTile(
    public val frames: List<AnimationFrame<TextureRegion>>,
    public val mode: PlaybackMode = PlaybackMode.LOOP,
    override val tint: Color = Color.WHITE,
    override val flipX: Boolean = false,
    override val flipY: Boolean = false,
) : DynamicSpriteTile {
    init {
        require(frames.isNotEmpty()) { "AnimatedSpriteTile requires at least one frame" }
        frames.forEachIndexed { index, frame ->
            require(frame.durationMs > 0) {
                "Frame $index has durationMs ${frame.durationMs}; every frame must have a positive duration"
            }
        }
    }

    /**
     * Returns the [TextureRegion] for the frame that is active at [elapsedMs]
     * milliseconds of wall-clock time. [PlaybackMode] governs what happens
     * after the sequence ends.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Negative values are treated as `0` (first frame).
     */
    override fun regionFor(elapsedMs: Long): TextureRegion = frames[frameIndexAt(frames, mode, elapsedMs)].content

    /**
     * [tint] multiplied by the active frame's [AnimationFrame.tint] (krogue-2ur), letting a
     * sprite shimmer by cycling per-frame color — e.g. a crystal wall's glow, the ASCII path's
     * native [com.sletmoe.kotile.display.ascii.AnimatedAsciiTile] equivalent. A frame with no
     * override (`tint == null`) contributes no change, so it renders at plain [tint] — existing
     * constant-tint animations (no frame ever sets [AnimationFrame.tint]) are unaffected.
     */
    override fun tintFor(elapsedMs: Long): Color {
        val frameTint = frames[frameIndexAt(frames, mode, elapsedMs)].tint ?: return tint
        return tint.cpy().mul(frameTint)
    }
}
