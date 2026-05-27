package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * A [Tile] that cycles through a sequence of [TextureRegion] frames over time.
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
 *   [Color.WHITE] (the default) leaves the sprite unchanged.
 * @throws IllegalArgumentException if [frames] is empty or any frame has a
 *   non-positive [AnimationFrame.durationMs].
 */
public class AnimatedSpriteTile(
    public val frames: List<AnimationFrame<TextureRegion>>,
    public val mode: PlaybackMode = PlaybackMode.LOOP,
    override val tint: Color = Color.WHITE,
) : Tile {
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
    override fun regionFor(elapsedMs: Long): TextureRegion =
        frames[frameIndexAt(frames, mode, elapsedMs)].content
}
