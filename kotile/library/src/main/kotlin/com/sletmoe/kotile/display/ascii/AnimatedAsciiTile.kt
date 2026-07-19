package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.tiles.frameIndexAt

/**
 * A [DynamicAsciiTile] that cycles through a sequence of [StaticAsciiTile]
 * frames over time.
 *
 * This enables Brogue-style visual effects such as lighting flicker, where a
 * floor cell's background color shifts subtly between frames to simulate the
 * warm, unsteady light of a torch.
 *
 * Time model is **stateless**: callers supply the elapsed wall-clock time on
 * every [resolveAt] call. The same instance may be placed at multiple grid
 * cells; all cells sharing the instance show the same frame at the same
 * wall-clock time. To display independent animation phases at different cells,
 * create separate instances or subtract a per-cell offset from [elapsedMs]
 * before calling [resolveAt].
 *
 * ## Construction example — floor cell with Brogue-style torch flicker
 * ```kotlin
 * val flicker = AnimatedAsciiTile(
 *     frames = listOf(
 *         AnimationFrame(StaticAsciiTile('.', Color.YELLOW, Color(0.4f, 0.2f, 0f, 1f)), 120),
 *         AnimationFrame(StaticAsciiTile('.', Color.YELLOW, Color(0.5f, 0.25f, 0f, 1f)), 80),
 *         AnimationFrame(StaticAsciiTile('.', Color.ORANGE, Color(0.45f, 0.22f, 0f, 1f)), 100),
 *     ),
 *     mode = PlaybackMode.PING_PONG,
 * )
 * ```
 *
 * @property frames ordered list of frames. Must not be empty; every frame's
 *   [AnimationFrame.durationMs] must be positive.
 * @property mode how the animation behaves once it reaches the end of the
 *   sequence; defaults to [PlaybackMode.LOOP].
 * @throws IllegalArgumentException if [frames] is empty or any frame has a
 *   non-positive [AnimationFrame.durationMs].
 */
public class AnimatedAsciiTile(
    public val frames: List<AnimationFrame<StaticAsciiTile>>,
    public val mode: PlaybackMode = PlaybackMode.LOOP,
) : DynamicAsciiTile {
    init {
        require(frames.isNotEmpty()) { "AnimatedAsciiTile requires at least one frame" }
        frames.forEachIndexed { index, frame ->
            require(frame.durationMs > 0) {
                "Frame $index has durationMs ${frame.durationMs}; every frame must have a positive duration"
            }
        }
    }

    /**
     * Returns the [StaticAsciiTile] for the frame active at [elapsedMs]
     * milliseconds of wall-clock time. [PlaybackMode] governs what happens
     * after the sequence ends.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Negative values are treated as `0` (first frame).
     */
    override fun resolveAt(elapsedMs: Long): StaticAsciiTile = frames[frameIndexAt(frames, mode, elapsedMs)].content
}
