package com.sletmoe.korogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import kotlinx.serialization.Serializable

// represent RGB data in a min-max normalized form
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
}

fun Color.toNormalizedRgb(): NormalizedRgb = NormalizedRgb(r.toDouble(), g.toDouble(), b.toDouble())

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
