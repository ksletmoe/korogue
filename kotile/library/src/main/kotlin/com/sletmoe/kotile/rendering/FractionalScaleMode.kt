package com.sletmoe.kotile.rendering

/**
 * How [com.sletmoe.kotile.display.KotileCanvas] smooths a **fractional** (non-integer)
 * fixed-grid scale. Integer scales and reflow are unaffected either way — they stay
 * nearest-neighbour and pixel-perfect; this only selects what happens when a cell
 * lands on a fractional on-screen size (ADR-0017, ADR-0036).
 *
 * A dedicated type rather than a boolean so further strategies can be added without
 * another ambiguous flag or a breaking constructor change (kotile is pre-1.0).
 */
enum class FractionalScaleMode {
    /**
     * The lighter default: a single-pass **sharp-bilinear** shader
     * ([SharpBilinear]) keeps texel interiors crisp and blends only a ~1px edge
     * band. Cheap, glyph *and* sprite, no extra buffer (krogue-m2x).
     */
    SHARP_BILINEAR,

    /**
     * Tier 2 (ADR-0036, krogue-1zo): capture the whole pass into an offscreen
     * [SupersampleTarget] at a large integer tile size (pixel-crisp) and resolve
     * to the window with a **gamma-correct** downsample. Higher fidelity at a
     * fractional scale (edges keep the correct brightness), at the cost of an
     * offscreen buffer per frame. Falls back to [SHARP_BILINEAR] if the downsample
     * shader or buffer is unavailable.
     */
    SUPERSAMPLE,
}
