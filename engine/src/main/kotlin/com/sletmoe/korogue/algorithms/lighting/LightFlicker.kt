package com.sletmoe.korogue.algorithms.lighting

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.sin

/**
 * Presentation-side flicker descriptor for a [com.sletmoe.korogue.components.LightEmitter]
 * (krogue-ncl, ADR-0023's presentation-time axis): a wall-clock intensity modulation, layered on
 * top of the tick-computed `Zone.lightMap` at render time — never recomputed into the saved light
 * field itself. Attaching one is opt-in; an emitter with no [LightEmitter.flicker] renders exactly
 * as before (a steady circle).
 *
 * Only [amplitude] and [periodMs] are static config (saved as part of the emitter, like [color]/
 * [radius]); the actual flicker *value* is a pure function of wall-clock time, computed fresh by
 * [factorAt] every frame and never persisted.
 *
 * @property amplitude how far the intensity swings from 1.0, e.g. `0.15` oscillates in
 *   `[0.85, 1.15]` before the caller clamps to a valid `[0, 1]` intensity
 * @property periodMs one full flicker cycle's length in wall-clock milliseconds
 * @property seed phase offset (ms) so multiple torches with identical amplitude/period don't
 *   flicker in lockstep — give each a distinct seed (e.g. derived from the entity id)
 */
@Serializable
data class LightFlicker(
    val amplitude: Double = 0.15,
    val periodMs: Long = 400L,
    val seed: Long = 0L,
) {
    /** Intensity multiplier at [elapsedMs] — oscillates smoothly around `1.0` within [amplitude]. */
    fun factorAt(elapsedMs: Long): Double {
        if (periodMs <= 0) return 1.0
        val phase = (elapsedMs + seed).mod(periodMs).toDouble() / periodMs
        return 1.0 + amplitude * sin(2 * PI * phase)
    }
}
