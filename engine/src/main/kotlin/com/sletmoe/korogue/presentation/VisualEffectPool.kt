package com.sletmoe.korogue.presentation

import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.TileSurface
import com.sletmoe.kotile.display.KotileCanvas

/**
 * A pool of independently-timed [VisualEvent]s that all play at once, with no input-gating
 * concept at all — the companion to [EventAnimationQueue] for the *other* common shape of visual
 * effect: cosmetic, fire-and-forget, and never something a game should pause turns for (multiple
 * simultaneous hit-flashes, a floating damage number stacking with the flash it accompanies, …).
 * [EventAnimationQueue] deliberately plays one sequence at a time — right for "gate input on a
 * bolt in flight" — but that means a caller wanting N simultaneous non-gating effects previously
 * had to hand-manage N separate `EventAnimationQueue` instances, one per concurrent effect, with
 * no engine support for it. This mirrors kotile's own [com.sletmoe.kotile.rendering.EffectsLayer]
 * primitive, which already models "many independent transient items" as a plain list — this is
 * that same shape one layer up, for presentation-side game events instead of raw sprites.
 *
 * Never serialized, never reads or writes game state — same presentation-only contract as
 * [EventAnimationQueue] (ADR-0023's third, event-animation time axis).
 */
class VisualEffectPool {
    private data class Entry(val event: VisualEvent, val sequence: VisualSequence, val startMs: Long)

    private val active = mutableListOf<Entry>()

    /** True while at least one effect is playing. Informational only — nothing gates on this. */
    val isPlaying: Boolean
        get() = active.isNotEmpty()

    /** Every [VisualEvent] currently playing, in spawn order — e.g. to find one at a particular cell. */
    val activeEvents: List<VisualEvent>
        get() = active.map { it.event }

    /** Starts playing [event] immediately, alongside whatever else is already active. */
    fun spawn(
        event: VisualEvent,
        nowMs: Long,
    ) {
        active.add(Entry(event, event.toSequence(), nowMs))
    }

    /** Drops every effect whose duration has elapsed as of [nowMs]. Call once per frame, before rendering. */
    fun update(nowMs: Long) {
        active.removeAll { nowMs - it.startMs >= it.sequence.durationMs }
    }

    /** Draws every active effect onto [surface] via [camera], in spawn order. No-op if none are active. */
    fun render(
        surface: TileSurface,
        camera: MapCamera,
        nowMs: Long,
    ) {
        for (entry in active) entry.sequence.render(surface, camera, nowMs - entry.startMs)
    }

    /**
     * The pixel-space counterpart to [render] (krogue-2ua): draws every active effect onto
     * [canvas] via [camera], self-contained ([KotileCanvas.begin]/[KotileCanvas.end] once around
     * all of them, matching [EventAnimationQueue.renderSprite]). No-op (no batch opened) if none
     * are active.
     */
    fun renderSprite(
        canvas: KotileCanvas,
        camera: MapCamera,
        nowMs: Long,
    ) {
        if (active.isEmpty()) return
        canvas.begin()
        for (entry in active) entry.sequence.renderSprite(canvas, camera, nowMs - entry.startMs)
        canvas.end()
    }

    /** Drops every active effect immediately. Call when the world it was animating is replaced (e.g. a load). */
    fun clear() {
        active.clear()
    }
}
