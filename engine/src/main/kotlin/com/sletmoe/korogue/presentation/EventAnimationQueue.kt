package com.sletmoe.korogue.presentation

import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.TileSurface

/**
 * Presentation-side queue of [VisualEvent]s (ADR-0023's third, event-animation time axis —
 * krogue-wuq): game systems publish an [EntityDamaged][com.sletmoe.korogue.events.EntityDamaged]-
 * style event, an observer translates it into a [VisualEvent] and [enqueue]s it, and this queue
 * plays them one at a time on the wall-clock. Never reads or writes game state and is never
 * serialized — a save/load taken mid-animation is identical to one taken without it. Ambient
 * animation (torch flicker, etc.) is untouched: it lives on the renderer's own wall-clock and
 * needs no awareness of this queue.
 *
 * The host game loop should:
 * 1. Call [update] once per frame with the same monotonic wall-clock passed to
 *    [com.sletmoe.kotile.display.ascii.AsciiTileWindow.render].
 * 2. Gate turn-advancing input on [isPlaying] (mirrors how a modal dialog pauses the world) —
 *    so the player sees a bolt land before acting again.
 * 3. Call [render] from the same place [MapPanel][com.sletmoe.korogue.ui.MapPanel] draws, passing
 *    its [MapCamera], so a sequence's world position tracks the same camera the map used this
 *    frame (see [MapCamera]'s "game-side overlays" seam).
 * 4. Offer [skip] on a keypress so a sequence can be fast-forwarded past.
 */
class EventAnimationQueue {
    private val pending = ArrayDeque<VisualSequence>()
    private var current: VisualSequence? = null
    private var currentStartMs: Long = 0L

    /** True while a sequence is playing or queued — callers gate turn-advancing input on this. */
    val isPlaying: Boolean
        get() = current != null || pending.isNotEmpty()

    /** Queues [event]'s sequence to play once any already-queued sequences finish. */
    fun enqueue(event: VisualEvent) {
        pending.addLast(event.toSequence())
    }

    /**
     * Advances the queue against [nowMs]: completes the head sequence once its duration has
     * elapsed and promotes the next queued one. Call once per frame, before [render].
     */
    fun update(nowMs: Long) {
        val head = current
        if (head != null && nowMs - currentStartMs >= head.durationMs) current = null
        if (current == null && pending.isNotEmpty()) {
            current = pending.removeFirst()
            currentStartMs = nowMs
        }
    }

    /** Draws the currently-playing sequence (if any) onto [surface] via [camera]. No-op otherwise. */
    fun render(
        surface: TileSurface,
        camera: MapCamera,
        nowMs: Long,
    ) {
        val head = current ?: return
        head.render(surface, camera, nowMs - currentStartMs)
    }

    /** Force-completes the head sequence immediately (a keypress fast-forwarding past it). */
    fun skip() {
        current = null
    }

    /**
     * Drops the head sequence and everything queued behind it. Call when the world it was
     * animating is replaced (e.g. a load) — a stale sequence's coordinates would otherwise resolve
     * against the new zone's camera on the next frame.
     */
    fun clear() {
        current = null
        pending.clear()
    }
}
