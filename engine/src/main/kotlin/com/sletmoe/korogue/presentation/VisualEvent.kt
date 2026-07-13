package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * A transient, presentation-only visual triggered by a game event (ADR-0023's third,
 * event-animation time axis — krogue-wuq): a thrown potion's arc, a bolt's flight, a hit-flash,
 * a death fade. Unlike ambient animation these have a definite start/end; unlike game time they
 * never read or write game state and are never serialized. Positions are world (zone) cells —
 * [EventAnimationQueue] resolves them to screen cells via the map's [com.sletmoe.korogue.ui.MapCamera]
 * at render time, so a sequence stays correct even if the camera re-centres while it plays.
 */
sealed interface VisualEvent {
    /** A brief flash at [at] (e.g. a hit landing). */
    data class HitFlash(
        val at: Vector2Int,
        val color: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_FLASH_MS,
    ) : VisualEvent

    /** [glyph]/[color] fading out at [at] (e.g. a death). */
    data class DeathFade(
        val at: Vector2Int,
        val glyph: Char,
        val color: Color,
        val durationMs: Long = DEFAULT_FADE_MS,
    ) : VisualEvent

    /**
     * [glyph] travelling from [from] to [to] over [durationMs], linearly interpolated (rounded
     * to the nearest cell each frame). This is the free-form render mode; a grid-snapped,
     * blocker-stopping cell-walk mode is krogue-tnf, layered on top of this same event type.
     */
    data class Projectile(
        val from: Vector2Int,
        val to: Vector2Int,
        val glyph: Char,
        val color: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_PROJECTILE_MS,
    ) : VisualEvent

    companion object {
        const val DEFAULT_FLASH_MS = 150L
        const val DEFAULT_FADE_MS = 300L
        const val DEFAULT_PROJECTILE_MS = 200L
    }
}
