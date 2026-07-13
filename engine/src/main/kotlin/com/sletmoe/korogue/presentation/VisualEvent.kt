package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
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
     * [glyph] travelling from [from] to [to] over [durationMs]. Two render modes, chosen by
     * whether [path] is set:
     * - `null` (default): free-form — linearly interpolated, rounded to the nearest cell each
     *   frame.
     * - non-null: grid-snapped (krogue-tnf) — steps through [path]'s cells in order, one per
     *   `durationMs / path.size`. Build it with
     *   [lineOfCellsStoppingAtBlocker][com.sletmoe.korogue.algorithms.geometry.lineOfCellsStoppingAtBlocker]
     *   so the bolt stops at the first blocker instead of passing through it; [from]/[to] should
     *   still be set to the path's actual endpoints (used only as a fallback if [path] is empty).
     *
     * @property backgroundAt supplies the background color for whatever cell the glyph currently
     *   occupies, given that cell and elapsedMs since this sequence started — lets a caller match
     *   its own terrain's lighting instead of the flat black [ProjectileSequence] otherwise draws
     *   (there's no read-back API on [com.sletmoe.korogue.ui.TileSurface] to sample it directly).
     *   `null` (default) keeps the flat-black background.
     */
    data class Projectile(
        val from: Vector2Int,
        val to: Vector2Int,
        val glyph: Char,
        val color: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_PROJECTILE_MS,
        val path: List<Vector2Int>? = null,
        val backgroundAt: ((at: Vector2Int, elapsedMs: Long) -> Color)? = null,
    ) : VisualEvent

    /**
     * [region] travelling from [from] to [to] over [durationMs], true sub-pixel motion drawn via
     * [com.sletmoe.kotile.display.KotileCanvas.drawSprite] (krogue-2ua) — the pixel-space/sprite
     * counterpart to [Projectile]'s free-form mode; there is no grid-snapped sprite mode (that's
     * what [Projectile] with a glyph is for). [nativeBearingDeg] is [region]'s own drawn facing
     * (`0` = pointing along `+X`/east); the renderer subtracts it from the computed travel bearing
     * so art that isn't drawn east-facing still ends up pointing the right way once rotated.
     *
     * @property stopShortPx how many content pixels short of [to]'s cell center the sprite stops,
     *   so a shot visibly lands just outside its target's cell instead of drawing on top of (or
     *   passing through) whatever occupies it. `0` (default) travels the full distance.
     */
    data class SpriteProjectile(
        val from: Vector2Int,
        val to: Vector2Int,
        val region: TextureRegion,
        val tint: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_PROJECTILE_MS,
        val nativeBearingDeg: Float = 0f,
        val stopShortPx: Float = 0f,
    ) : VisualEvent

    companion object {
        const val DEFAULT_FLASH_MS = 150L
        const val DEFAULT_FADE_MS = 300L
        const val DEFAULT_PROJECTILE_MS = 200L
    }
}
