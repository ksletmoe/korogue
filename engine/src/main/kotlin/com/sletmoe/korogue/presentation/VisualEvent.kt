package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * A transient, presentation-only visual triggered by a game event (ADR-0023's third,
 * event-animation time axis — krogue-wuq): a thrown potion's arc, a bolt's flight, a hit-flash,
 * a death fade. Unlike ambient animation these have a definite start/end; unlike game time they
 * never read or write game state and are never serialized. Positions are world (zone) cells —
 * [EventAnimationQueue] resolves them to screen cells via the map's [com.sletmoe.korogue.ui.MapCamera]
 * at render time, so a sequence stays correct even if the camera re-centres while it plays.
 *
 * Open, not sealed — mirroring [com.sletmoe.korogue.ecs.Event] (ADR-0010), the engine's existing
 * precedent for this exact shape: an open marker interface implemented by concrete game/engine
 * types, dispatched by (in `Event`'s case) concrete class, rather than a closed hierarchy matched
 * in a central `when`. A game defines its own `VisualEvent` (a screen shake, an area burst) with
 * its own [toSequence] and enqueues it into [EventAnimationQueue] exactly like a built-in one — no
 * engine change needed, unlike a sealed hierarchy would require (the "closed struct" ADR-0014/
 * ADR-0015 call out as the anti-pattern pluggable-policy concerns should avoid).
 */
interface VisualEvent {
    /** The [VisualSequence] that plays this event, built once when it's [EventAnimationQueue.enqueue]d. */
    fun toSequence(): VisualSequence

    /** A brief flash at [at] (e.g. a hit landing). */
    data class HitFlash(
        val at: Vector2Int,
        val color: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_FLASH_MS,
    ) : VisualEvent {
        override fun toSequence(): VisualSequence = HitFlashSequence(this)
    }

    /** [glyph]/[color] fading out at [at] (e.g. a death). */
    data class DeathFade(
        val at: Vector2Int,
        val glyph: Char,
        val color: Color,
        val durationMs: Long = DEFAULT_FADE_MS,
    ) : VisualEvent {
        override fun toSequence(): VisualSequence = DeathFadeSequence(this)
    }

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
     *   its own terrain's lighting instead of the flat black [GlyphProjectileSequence] otherwise
     *   draws (there's no read-back API on [com.sletmoe.korogue.ui.TileSurface] to sample it
     *   directly). `null` (default) keeps the flat-black background.
     * @property tintAt supplies the foreground color for whatever cell the glyph currently occupies,
     *   given that cell, elapsedMs, and [color] to tint (krogue-ea7) — lets a caller apply the same
     *   per-cell lighting terrain and occupants already get
     *   ([com.sletmoe.korogue.ui.MapPanel]'s `litColor`, krogue-0w3) instead of the flat,
     *   wall-clock-constant [color] this sequence otherwise draws throughout its whole flight.
     *   `null` (default) keeps [color] unchanged.
     */
    data class GlyphProjectile(
        val from: Vector2Int,
        val to: Vector2Int,
        val glyph: Char,
        val color: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_PROJECTILE_MS,
        val path: List<Vector2Int>? = null,
        val backgroundAt: ((at: Vector2Int, elapsedMs: Long) -> Color)? = null,
        val tintAt: ((at: Vector2Int, elapsedMs: Long, base: Color) -> Color)? = null,
    ) : VisualEvent {
        override fun toSequence(): VisualSequence = GlyphProjectileSequence(this)
    }

    /**
     * [region] travelling from [from] to [to] over [durationMs], true sub-pixel motion drawn via
     * [com.sletmoe.kotile.display.KotileCanvas.drawSprite] (krogue-2ua) — the pixel-space/sprite
     * counterpart to [GlyphProjectile]'s free-form mode; there is no grid-snapped sprite mode
     * (that's what [GlyphProjectile] with a glyph is for). [nativeBearingDeg] is [region]'s own drawn facing
     * (`0` = pointing along `+X`/east); the renderer subtracts it from the computed travel bearing
     * so art that isn't drawn east-facing still ends up pointing the right way once rotated.
     *
     * @property stopShortPx how many content pixels short of [to]'s cell center the sprite stops,
     *   so a shot visibly lands just outside its target's cell instead of drawing on top of (or
     *   passing through) whatever occupies it. `0` (default) travels the full distance.
     * @property tintAt supplies the tint for whatever world cell the sprite currently occupies
     *   (interpolated from [from]/[to] the same way the drawn position is, krogue-ea7), given that
     *   cell, elapsedMs, and [tint] to tint — the sprite-space counterpart to [GlyphProjectile.tintAt].
     *   `null` (default) keeps [tint] unchanged.
     */
    data class SpriteProjectile(
        val from: Vector2Int,
        val to: Vector2Int,
        val region: TextureRegion,
        val tint: Color = Color.WHITE,
        val durationMs: Long = DEFAULT_PROJECTILE_MS,
        val nativeBearingDeg: Float = 0f,
        val stopShortPx: Float = 0f,
        val tintAt: ((at: Vector2Int, elapsedMs: Long, base: Color) -> Color)? = null,
    ) : VisualEvent {
        override fun toSequence(): VisualSequence = SpriteProjectileSequence(this)
    }

    /**
     * [text] rising and fading above [at] over [durationMs] (e.g. a floating damage number) —
     * sprite-space only, drawn character-by-character via [font]'s glyph regions through
     * [com.sletmoe.kotile.display.KotileCanvas.drawSprite]. No glyph/[com.sletmoe.korogue.ui.TileSurface]
     * counterpart yet — not because one is structurally impossible ("-4" over two ASCII cells,
     * background-color fade instead of a true rise, is a plausible approximation), just because
     * nothing has needed it so far; add one if/when a game does.
     *
     * @property font glyph source (caller-owned; this event borrows it, never disposes it)
     * @property charWidthPx/[charHeightPx] on-screen size (content pixels — the same space as
     *   [com.sletmoe.kotile.rendering.GridLayout.tileWidthPx]) to draw each character at
     * @property riseDistancePx how far up (content pixels) the text drifts over the full
     *   [durationMs], fading out linearly as it rises
     */
    data class FloatingText(
        val at: Vector2Int,
        val text: String,
        val font: Font,
        val charWidthPx: Float,
        val charHeightPx: Float,
        val color: Color = Color.RED,
        val durationMs: Long = DEFAULT_FLOATING_TEXT_MS,
        val riseDistancePx: Float = charHeightPx * 1.5f,
    ) : VisualEvent {
        override fun toSequence(): VisualSequence = FloatingTextSequence(this)
    }

    companion object {
        const val DEFAULT_FLASH_MS = 150L
        const val DEFAULT_FADE_MS = 300L
        const val DEFAULT_PROJECTILE_MS = 200L
        const val DEFAULT_FLOATING_TEXT_MS = 900L
    }
}
