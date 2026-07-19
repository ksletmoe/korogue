package com.sletmoe.korogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.Serializable

/**
 * A colour in korogue's **model** vocabulary: three channels held as [Double], deeply
 * immutable and [Serializable]. This is the type a consumer authors into components
 * ([com.sletmoe.korogue.components.Renderable.color],
 * [com.sletmoe.korogue.components.LightEmitter.color]) and light values. libGDX's mutable
 * float [Color] is the **presentation** type; the renderer converts model → presentation with
 * [toColor] at the draw boundary. Which type a consumer touches, and why, is ADR-0035.
 *
 * Channels are **not** clamped in model space — a blend ([times]) or scalar scale can push a
 * channel above 1.0 (an over-bright light) or, via a difference, below 0.0. [toColor] clamps to
 * the displayable `[0, 1]` range at the boundary, so out-of-gamut intermediates compose correctly.
 *
 * Prefer the [companion][Companion] constants and factories over reaching into GDX's [Color]
 * palette: `NormalizedRgb.YELLOW` rather than `Color.YELLOW.toNormalizedRgb()`. For a named GDX
 * colour this type does not re-export, use [fromColor]; [Color.toNormalizedRgb] remains as the
 * receiver-style spelling of the same conversion.
 */
@Serializable
data class NormalizedRgb(val r: Double, val g: Double, val b: Double) {
    fun toColor(): Color =
        Color(
            r.toFloat().coerceIn(0f, 1f),
            g.toFloat().coerceIn(0f, 1f),
            b.toFloat().coerceIn(0f, 1f),
            1f,
        )

    // for color multiplication https://en.wikipedia.org/wiki/Blend_modes#Multiply
    operator fun times(other: NormalizedRgb): NormalizedRgb = NormalizedRgb(r * other.r, g * other.g, b * other.b)

    operator fun times(multiplier: Double): NormalizedRgb =
        NormalizedRgb(r * multiplier, g * multiplier, b * multiplier)

    /**
     * Per-channel linear interpolation from this colour toward [other]: `t = 0.0` returns this
     * colour, `t = 1.0` returns [other], `0.5` the midpoint. [t] is not clamped, matching the
     * unclamped model-space contract above.
     */
    fun lerp(
        other: NormalizedRgb,
        t: Double,
    ): NormalizedRgb =
        NormalizedRgb(
            r + (other.r - r) * t,
            g + (other.g - g) * t,
            b + (other.b - b) * t,
        )

    companion object {
        // The neutral identities are model-space literals: BLACK is the multiply/tint-to-dark
        // absorber, WHITE the multiply/light identity. The rest re-export GDX's named palette
        // (single source of truth) into model space so component authors never import Color.
        val BLACK = NormalizedRgb(0.0, 0.0, 0.0)
        val WHITE = NormalizedRgb(1.0, 1.0, 1.0)
        val GRAY = fromColor(Color.GRAY)
        val LIGHT_GRAY = fromColor(Color.LIGHT_GRAY)
        val DARK_GRAY = fromColor(Color.DARK_GRAY)
        val RED = fromColor(Color.RED)
        val GREEN = fromColor(Color.GREEN)
        val BLUE = fromColor(Color.BLUE)
        val YELLOW = fromColor(Color.YELLOW)
        val GOLD = fromColor(Color.GOLD)
        val ORANGE = fromColor(Color.ORANGE)
        val CYAN = fromColor(Color.CYAN)
        val MAGENTA = fromColor(Color.MAGENTA)
        val PURPLE = fromColor(Color.PURPLE)
        val BROWN = fromColor(Color.BROWN)

        /** The model form of a presentation [color]; drops alpha (the model is opaque RGB). */
        fun fromColor(color: Color): NormalizedRgb =
            NormalizedRgb(color.r.toDouble(), color.g.toDouble(), color.b.toDouble())

        /**
         * Parse a hex string into a model colour via GDX's [Color.valueOf] (`"RRGGBB"` or
         * `"RRGGBBAA"`, optional leading `#`). Alpha, if present, is dropped.
         */
        fun fromHex(hex: String): NormalizedRgb = fromColor(Color.valueOf(hex))
    }
}

fun Color.toNormalizedRgb(): NormalizedRgb = NormalizedRgb.fromColor(this)

/**
 * [this] (a base terrain/tile color) tinted by a light source's [lightColor] scaled by
 * [intensity], fused into a single allocation — the returned [Color] — rather than the
 * three-object chain `(this.toNormalizedRgb() * (lightColor * intensity)).toColor()` computes
 * (one [toNormalizedRgb] result, one [NormalizedRgb.times] result, one final [Color]). Callers
 * that recompute a tinted color per visible cell every frame (e.g. `MapPanel.terrainCell`/
 * `backgroundAt`) do this often enough that the two intermediate allocations are worth cutting.
 */
fun Color.tintedByLight(
    lightColor: NormalizedRgb,
    intensity: Double,
): Color {
    val tintedR = (r * lightColor.r * intensity).toFloat().coerceIn(0f, 1f)
    val tintedG = (g * lightColor.g * intensity).toFloat().coerceIn(0f, 1f)
    val tintedB = (b * lightColor.b * intensity).toFloat().coerceIn(0f, 1f)
    return Color(tintedR, tintedG, tintedB, 1f)
}
