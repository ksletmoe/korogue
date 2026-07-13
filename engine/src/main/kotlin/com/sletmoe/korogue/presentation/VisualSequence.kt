package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.TileSurface
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.roundToInt

/**
 * One running [VisualEvent] translated into wall-clock draw calls. Stateless like
 * [com.sletmoe.kotile.display.ascii.AnimatedAsciiTile]: [render] computes the frame purely from
 * [elapsedMs] since the sequence started, so nothing here needs its own timers. Draws on
 * [RenderLayer.OVERLAY]'s z-band — above creatures/player, cleared for free every frame since the
 * whole map redraws from scratch (no explicit residue cleanup needed).
 */
interface VisualSequence {
    /** How long this sequence plays for, in wall-clock ms. */
    val durationMs: Long

    /** Draws this sequence's state at [elapsedMs] (0..[durationMs]) onto [surface] via [camera]. */
    fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    )
}

/** Converts a [VisualEvent] into the [VisualSequence] that plays it. */
fun VisualEvent.toSequence(): VisualSequence =
    when (this) {
        is VisualEvent.HitFlash -> HitFlashSequence(this)
        is VisualEvent.DeathFade -> DeathFadeSequence(this)
        is VisualEvent.Projectile -> ProjectileSequence(this)
    }

/** [VisualEvent.HitFlash] draws solid for its whole (short) duration. */
private class HitFlashSequence(private val event: VisualEvent.HitFlash) : VisualSequence {
    override val durationMs: Long get() = event.durationMs

    override fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    ) {
        putAt(surface, camera, event.at, HIT_GLYPH, event.color)
    }

    private companion object {
        const val HIT_GLYPH = '*'
    }
}

/** [VisualEvent.DeathFade] darkens [VisualEvent.DeathFade.color] linearly to black over [durationMs]. */
private class DeathFadeSequence(private val event: VisualEvent.DeathFade) : VisualSequence {
    override val durationMs: Long get() = event.durationMs

    override fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    ) {
        val remaining = (1f - elapsedMs.toFloat() / event.durationMs).coerceIn(0f, 1f)
        val faded = event.color.cpy().mul(remaining, remaining, remaining, 1f)
        putAt(surface, camera, event.at, event.glyph, faded)
    }
}

/** [VisualEvent.Projectile] linearly interpolates from -> to, rounded to the nearest cell. */
private class ProjectileSequence(private val event: VisualEvent.Projectile) : VisualSequence {
    override val durationMs: Long get() = event.durationMs

    override fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    ) {
        val t = (elapsedMs.toFloat() / event.durationMs).coerceIn(0f, 1f)
        val x = (event.from.x + (event.to.x - event.from.x) * t).roundToInt()
        val y = (event.from.y + (event.to.y - event.from.y) * t).roundToInt()
        putAt(surface, camera, Vector2Int(x, y), event.glyph, event.color)
    }
}

/**
 * Writes [glyph]/[color] at world cell [at] via [camera], on [RenderLayer.OVERLAY]'s z-band.
 * The overlay layer fully replaces (not blends) whatever the terrain/occupant layers drew at
 * that cell (top-cell-wins compositing), so the background is set to black rather than sampled
 * from beneath — a deliberate MVP simplification (no read-back API on [TileSurface]); acceptable
 * for a sub-second flash/fade/bolt, revisit if it looks wrong once ranged combat lands.
 */
private fun putAt(
    surface: TileSurface,
    camera: MapCamera,
    at: Vector2Int,
    glyph: Char,
    color: Color,
) {
    val screenX = camera.screenX(at.x)
    val screenY = camera.screenY(at.y)
    if (!camera.containsScreen(screenX, screenY)) return
    surface.put(screenX, screenY, RenderLayer.OVERLAY.zIndex, glyph, color, Color.BLACK)
}
