package com.sletmoe.kotile.tiles

/**
 * Controls how an animated tile cycles through its frame sequence once the
 * total duration has elapsed.
 */
public enum class PlaybackMode {
    /**
     * Plays frames forward in order and wraps back to frame 0 after the last
     * frame. The animation runs indefinitely.
     */
    LOOP,

    /**
     * Plays frames forward in order and holds on the last frame once the
     * sequence has finished. The animation stops after one full pass.
     */
    ONCE,

    /**
     * Plays frames forward to the last frame then backward to the first frame,
     * repeating indefinitely. The first and last frames are not duplicated at
     * the reversal points.
     *
     * For a sequence of N frames the ping-pong period covers `2 * (N - 1)`
     * steps (N >= 2). For a single-frame sequence this behaves identically to
     * [LOOP].
     */
    PING_PONG,
}
