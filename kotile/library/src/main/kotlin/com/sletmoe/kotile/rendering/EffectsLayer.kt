package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import kotlin.math.atan2

/**
 * The [KotileCanvas.drawSprite]/[Effect.rotationDeg] angle, in degrees, pointing along
 * ([velX], [velY]) — content-pixel-space (top-left-origin, Y-down) velocity components, e.g.
 * an [Effect]'s own [Effect.velXPerMs]/[Effect.velYPerMs]. `0` points along `+X` (right);
 * positive angles rotate clockwise as drawn on screen, matching [KotileCanvas.drawSprite]'s
 * own convention. A zero vector yields `0`.
 */
fun rotationTowards(velX: Float, velY: Float): Float =
    if (velX == 0f && velY == 0f) 0f else Math.toDegrees(atan2(velY.toDouble(), velX.toDouble())).toFloat()

/**
 * A single transient item drawn by an [EffectsLayer]: a sprite/glyph at a float
 * pixel position that moves linearly and expires after a lifetime.
 *
 * Position is the sprite's **top-left** corner in the canvas's content-pixel
 * space — the same space and origin as [KotileCanvas.drawSprite]. To place an
 * effect at the center of cell `(c, r)` given a live layout, offset by half a
 * tile and half the sprite:
 * `pxX = (c + 0.5f) * layout.tileWidthPx - w / 2f` (and likewise for y).
 *
 * Motion is linear: [velXPerMs]/[velYPerMs] are content pixels per millisecond,
 * applied on each [EffectsLayer.update]. Richer paths and easing are a
 * presentation-side concern layered on top (the event-animation queue,
 * krogue-wuq), not this primitive.
 *
 * [rotationDeg] is a plain, static field like [tint] — [EffectsLayer] never
 * derives it from [velXPerMs]/[velYPerMs] itself (an effect need not be an
 * arrow that points where it's going). A caller wanting a projectile to face
 * its direction of travel sets it once at spawn via [rotationTowards], since
 * motion here is linear (constant velocity, so the facing angle never changes).
 *
 * @property pxX current top-left x in content pixels (advances with velocity)
 * @property pxY current top-left y in content pixels (advances with velocity)
 * @property w on-screen width in content pixels
 * @property h on-screen height in content pixels
 * @property region the texture region (sprite or glyph) to draw
 * @property tint color multiplier (white = unchanged)
 * @property velXPerMs horizontal velocity, content pixels per millisecond
 * @property velYPerMs vertical velocity, content pixels per millisecond
 * @property rotationDeg rotation passed to [KotileCanvas.drawSprite] (see its
 *   doc for the angle convention); `0` (default) draws unrotated
 * @property lifetimeMs how long the effect stays active before it expires and is
 *   dropped; `null` means it never expires on its own (remove it explicitly)
 */
class Effect(
    var pxX: Float,
    var pxY: Float,
    val w: Float,
    val h: Float,
    var region: TextureRegion,
    var tint: Color = Color.WHITE,
    var velXPerMs: Float = 0f,
    var velYPerMs: Float = 0f,
    var rotationDeg: Float = 0f,
    val lifetimeMs: Long? = null,
) {
    /** Milliseconds this effect has been active (accumulated across updates). */
    var ageMs: Long = 0L
        internal set

    /** True once [ageMs] has reached a finite [lifetimeMs]; such effects are dropped on update. */
    val expired: Boolean get() = lifetimeMs != null && ageMs >= lifetimeMs
}

/**
 * A free (pixel-space) [Layer] of transient [Effect]s — projectiles, particles,
 * beams, hit sparks — that fly across the tile grid at sub-tile resolution and
 * interpolate smoothly between cells (ADR-0018). The first consumer of the
 * [KotileCanvas.drawSprite] primitive.
 *
 * Stack it **above** the grid layer(s) so effects draw on top:
 * ```kotlin
 * val effects = EffectsLayer()
 * stack.add(mapRenderer.asLayer { elapsedMs }) // grid, below
 * stack.add(effects)                            // effects, on top
 * // each frame:
 * effects.spawn(Effect(...))    // when a game event fires
 * stack.update(dtMs)            // advances + expires effects
 * stack.render()                // draws survivors via drawSprite
 * ```
 *
 * [update] advances every effect by the frame delta and drops any that have
 * [Effect.expired]; [render] draws the survivors in spawn order. Effects manage
 * only their own items — they never add or remove layers from the enclosing
 * [LayerStack], so they respect its no-structural-modification-mid-frame rule.
 */
class EffectsLayer : Layer {
    private val effects = mutableListOf<Effect>()

    /** Number of currently-active effects. */
    val activeCount: Int get() = effects.size

    /** Adds [effect] to the layer and returns it (so the caller can keep a handle). */
    fun spawn(effect: Effect): Effect {
        effects.add(effect)
        return effect
    }

    /** Removes [effect] before it expires on its own. Returns `true` if it was present. */
    fun remove(effect: Effect): Boolean = effects.remove(effect)

    /** Removes every active effect. */
    fun clear() {
        effects.clear()
    }

    /**
     * Advances every effect by [dtMs] milliseconds (age + linear motion) and
     * drops any that have expired. Called by [LayerStack.update].
     */
    override fun update(dtMs: Long) {
        val iterator = effects.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            effect.ageMs += dtMs
            effect.pxX += effect.velXPerMs * dtMs
            effect.pxY += effect.velYPerMs * dtMs
            if (effect.expired) iterator.remove()
        }
    }

    /** Draws every active effect via [KotileCanvas.drawSprite], in spawn order. */
    override fun render(canvas: KotileCanvas) {
        for (effect in effects) {
            canvas.drawSprite(effect.pxX, effect.pxY, effect.region, effect.w, effect.h, effect.tint, effect.rotationDeg)
        }
    }
}
