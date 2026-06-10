package com.sletmoe.kotile.tiles

/**
 * Stateless helper that maps a wall-clock [elapsedMs] value to a frame index
 * within a list of [frames] under the given [mode].
 *
 * This function is internal — consumers use [AnimatedSpriteTile] or
 * [com.sletmoe.kotile.display.ascii.AnimatedAsciiTile] directly.
 */
internal fun <T> frameIndexAt(
    frames: List<AnimationFrame<T>>,
    mode: PlaybackMode,
    elapsedMs: Long,
): Int {
    val n = frames.size

    // Accumulate per-frame durations once; these lists are built at
    // construction time so this loop runs over the (usually small) frame list.
    val starts = LongArray(n)
    var total = 0L
    for (i in 0 until n) {
        starts[i] = total
        total += frames[i].durationMs
    }

    // Single frame — always return it regardless of mode or time.
    if (n == 1) return 0

    val clampedElapsed = maxOf(0L, elapsedMs)

    return when (mode) {
        PlaybackMode.LOOP -> {
            val pos = clampedElapsed % total
            indexForPosition(starts, pos)
        }

        PlaybackMode.ONCE -> {
            if (clampedElapsed >= total) return n - 1
            indexForPosition(starts, clampedElapsed)
        }

        PlaybackMode.PING_PONG -> {
            // Period = forward pass + backward pass, sharing neither endpoint.
            // e.g. frames [A, B, C] -> period = A B C B (length = 4 steps = 4 durations)
            // We build a virtual sequence of length 2*(n-1) and index into it.
            val pingPongTotal = 2 * (total - frames.last().durationMs)
            val pos = clampedElapsed % pingPongTotal
            if (pos < total) {
                // Forward pass
                indexForPosition(starts, pos)
            } else {
                // Backward pass: position within the backward half starts at 0
                // when we cross from forward to backward at pos == total.
                val backward = pos - total
                // Walk the frames in reverse (skip last, which was already shown)
                val reverseStarts = LongArray(n - 1)
                var acc = 0L
                for (i in n - 2 downTo 0) {
                    reverseStarts[n - 2 - i] = acc
                    acc += frames[i].durationMs
                }
                val revIdx = indexForPosition(reverseStarts, backward)
                n - 2 - revIdx
            }
        }
    }
}

/** Binary-searches [starts] for the last entry <= [pos] and returns its index. */
private fun indexForPosition(starts: LongArray, pos: Long): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= pos) lo = mid else hi = mid - 1
    }
    return lo
}
