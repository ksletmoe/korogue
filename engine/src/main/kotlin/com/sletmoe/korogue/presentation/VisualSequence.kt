package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.TileSurface
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.rotationTowards
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.math.roundToInt

/**
 * One running [VisualEvent] translated into wall-clock draw calls. Stateless like
 * [com.sletmoe.kotile.display.ascii.AnimatedAsciiTile]: [render]/[renderSprite] compute the frame
 * purely from [elapsedMs] since the sequence started, so nothing here needs its own timers. Draws
 * on [RenderLayer.OVERLAY]'s z-band — above creatures/player, cleared for free every frame since
 * the whole map redraws from scratch (no explicit residue cleanup needed).
 *
 * Two independent render seams, not one dispatched by game mode: [render] targets the ASCII/glyph
 * [TileSurface] path, [renderSprite] the pixel-space [KotileCanvas] path (krogue-2ua) — a sequence
 * overrides whichever it supports (both default to a no-op) so the same event can, if a caller
 * wants, drive both a sprite view and a glyph view of the same moment at once (the animation
 * showcase, krogue-aqo, does exactly this).
 */
interface VisualSequence {
    /** How long this sequence plays for, in wall-clock ms. */
    val durationMs: Long

    /** Draws this sequence's state at [elapsedMs] (0..[durationMs]) onto [surface] via [camera]. */
    fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    ) {}

    /** Draws this sequence's state at [elapsedMs] (0..[durationMs]) onto [canvas] via [camera]. */
    fun renderSprite(
        canvas: KotileCanvas,
        camera: MapCamera,
        elapsedMs: Long,
    ) {}
}

/** Converts a [VisualEvent] into the [VisualSequence] that plays it. */
fun VisualEvent.toSequence(): VisualSequence =
    when (this) {
        is VisualEvent.HitFlash -> HitFlashSequence(this)
        is VisualEvent.DeathFade -> DeathFadeSequence(this)
        is VisualEvent.Projectile -> ProjectileSequence(this)
        is VisualEvent.SpriteProjectile -> SpriteProjectileSequence(this)
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

/**
 * [VisualEvent.Projectile] — free-form (no [VisualEvent.Projectile.path]) linearly interpolates
 * from -> to, rounded to the nearest cell; grid-snapped (krogue-tnf, [VisualEvent.Projectile.path]
 * set) steps through the path's cells in order instead.
 */
private class ProjectileSequence(private val event: VisualEvent.Projectile) : VisualSequence {
    override val durationMs: Long get() = event.durationMs

    override fun render(
        surface: TileSurface,
        camera: MapCamera,
        elapsedMs: Long,
    ) {
        val path = event.path
        val t = (elapsedMs.toFloat() / event.durationMs).coerceIn(0f, 1f)
        val at =
            if (path.isNullOrEmpty()) {
                val x = (event.from.x + (event.to.x - event.from.x) * t).roundToInt()
                val y = (event.from.y + (event.to.y - event.from.y) * t).roundToInt()
                Vector2Int(x, y)
            } else {
                path[(t * path.size).toInt().coerceIn(0, path.size - 1)]
            }
        putAt(surface, camera, at, event.glyph, event.color)
    }
}

/**
 * [VisualEvent.SpriteProjectile] — true sub-pixel linear interpolation from -> to via
 * [KotileCanvas.drawSprite] (krogue-2ua), the pixel-space counterpart to [ProjectileSequence]'s
 * free-form mode. [VisualEvent.SpriteProjectile.nativeBearingDeg] compensates for source art that
 * isn't drawn pointing along `+X`, so the final rotation still faces the true travel direction.
 */
private class SpriteProjectileSequence(private val event: VisualEvent.SpriteProjectile) : VisualSequence {
    override val durationMs: Long get() = event.durationMs

    override fun renderSprite(
        canvas: KotileCanvas,
        camera: MapCamera,
        elapsedMs: Long,
    ) {
        val l = canvas.layout
        val fromScreen = Vector2Int(camera.screenX(event.from.x), camera.screenY(event.from.y))
        val toScreen = Vector2Int(camera.screenX(event.to.x), camera.screenY(event.to.y))
        val fromPxX = (fromScreen.x + 0.5f) * l.tileWidthPx
        val fromPxY = (fromScreen.y + 0.5f) * l.tileHeightPx
        val toPxX = (toScreen.x + 0.5f) * l.tileWidthPx
        val toPxY = (toScreen.y + 0.5f) * l.tileHeightPx

        val t = (elapsedMs.toFloat() / event.durationMs).coerceIn(0f, 1f)
        val pxX = fromPxX + (toPxX - fromPxX) * t
        val pxY = fromPxY + (toPxY - fromPxY) * t

        val screenX = (pxX / l.tileWidthPx).toInt()
        val screenY = (pxY / l.tileHeightPx).toInt()
        if (!camera.containsScreen(screenX, screenY)) return

        val rotationDeg = rotationTowards(toPxX - fromPxX, toPxY - fromPxY) - event.nativeBearingDeg
        canvas.drawSprite(
            pxX = pxX - l.tileWidthPx / 2f,
            pxY = pxY - l.tileHeightPx / 2f,
            region = event.region,
            w = l.tileWidthPx,
            h = l.tileHeightPx,
            tint = event.tint,
            rotationDeg = rotationDeg,
        )
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
