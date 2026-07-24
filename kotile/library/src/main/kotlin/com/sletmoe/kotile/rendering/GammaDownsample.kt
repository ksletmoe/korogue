package com.sletmoe.kotile.rendering

import kotlin.math.pow

/**
 * sRGB ↔ linear-light conversion (the standard IEC 61966-2-1 transfer function),
 * as pure Kotlin so the colour math the [GammaDownsample] shader relies on is
 * unit-testable without a GL context — and so the shader and any reference
 * computation stay in lockstep (the GLSL in [GammaDownsample] implements the
 * *same* piecewise curve, not a `pow(2.2)` approximation).
 *
 * ## Why it matters (ADR-0036, tier 2 / krogue-1zo)
 *
 * 8-bit colour channels are stored **sRGB-encoded** (perceptually spaced), not
 * proportional to light. Averaging two encoded values — what a plain bilinear
 * downsample does — averages the *encoding*, not the light, and comes out too
 * dark: the midpoint of black (0) and white (255) is 128 in encoded space but
 * corresponds to only ~22% of white's actual light. The perceptually correct
 * average is done in **linear light** (decode → average → re-encode) and lands at
 * ~188. That ~60-level gap is the whole point of a gamma-correct downsample and is
 * exactly what [com.sletmoe.kotile.rendering.SupersampleTarget]'s resolve pass does
 * — the cheap, high-value lesson lifted from Brogue's `downscaleTile`.
 */
internal object GammaColor {
    /** Decodes one sRGB-encoded channel value in `[0, 1]` to linear light. */
    fun toLinear(srgb: Float): Float = if (srgb <= 0.04045f) srgb / 12.92f else ((srgb + 0.055f) / 1.055f).pow(2.4f)

    /** Encodes one linear-light channel value in `[0, 1]` back to sRGB. */
    fun toSrgb(linear: Float): Float =
        if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
}

/**
 * GLSL for the **gamma-correct downsample** that resolves the supersampled scene
 * framebuffer to the window at a fractional scale (ADR-0036 tier 2; see
 * [com.sletmoe.kotile.rendering.SupersampleTarget] for how it is wired, and
 * [GammaColor] for the why and the matching CPU-side curve).
 *
 * The scene is rendered into an offscreen buffer at a **large integer** tile size
 * — so glyphs and sprite tiles are pixel-crisp there — then this shader shrinks it
 * to the exact on-screen size, box-averaging each destination pixel's source
 * footprint **in linear light** rather than in sRGB-encoded space. That single
 * mechanism covers both the glyph and sprite layers (everything drawn through the
 * canvas funnels through the one scene buffer) and keeps edges the correct
 * brightness at any window size.
 *
 * ## Footprint and taps
 *
 * The captured scene is at most 2× the on-screen size per axis (the integer tile
 * size is `ceil(onScreenScale)` × native, and `ceil(s)/s < 2`), so a **2×2** tap
 * grid spanning the destination pixel's source footprint area-averages it
 * adequately; a wider filter would only blur. Taps sit at ±¼ of the footprint
 * from the pixel centre and each uses hardware `Linear` sampling, so the four taps
 * already blend within their own quarter before the linear-light average.
 *
 * ## Alpha
 *
 * Glyphs are drawn over a transparent scene, so downsampling straddles opaque
 * glyph texels and transparent background. Colour is averaged **weighted by
 * alpha** (i.e. in premultiplied form) and un-premultiplied at the end, so a
 * fringe of colour does not bleed out of a glyph edge into its transparent
 * surround. Alpha itself is averaged straight. The over-background composite that
 * follows is still the batch's normal sRGB-space blend — matching Brogue's full
 * linear compositing pipeline is a deferred tier-3 refinement, not part of this.
 *
 * ## Uniforms
 *
 * - `u_texture` — the scene colour buffer (unit 0), `Linear` filtered.
 * - `u_footprintTexels` — source texels covered by one destination pixel, per axis
 *   (the downscale ratio); governs how far apart the four taps sit.
 * - `u_srcTexel` — `1 / sceneSize` per axis, converting the texel-space footprint
 *   into a UV offset.
 */
internal object GammaDownsample {
    /** Standard libGDX SpriteBatch vertex shader (unchanged default vertex path). */
    val VERTEX: String =
        """
        attribute vec4 a_position;
        attribute vec4 a_color;
        attribute vec2 a_texCoord0;
        uniform mat4 u_projTrans;
        varying vec4 v_color;
        varying vec2 v_texCoords;

        void main() {
            v_color = a_color;
            v_color.a = v_color.a * (255.0 / 254.0);
            v_texCoords = a_texCoord0;
            gl_Position = u_projTrans * a_position;
        }
        """.trimIndent()

    /**
     * Gamma-correct 2×2 box-downsample fragment shader. Requires `u_texture` to
     * use a `Linear` mag/min filter. The `toLinear`/`toSrgb` functions are the
     * exact GLSL twins of [GammaColor] — keep them identical if either changes.
     */
    val FRAGMENT: String =
        """
        #ifdef GL_ES
        precision highp float;
        #endif

        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform vec2 u_footprintTexels;
        uniform vec2 u_srcTexel;

        vec3 toLinear(vec3 c) {
            vec3 lo = c / 12.92;
            vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), c));
        }

        vec3 toSrgb(vec3 c) {
            vec3 lo = c * 12.92;
            vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
            return mix(lo, hi, step(vec3(0.0031308), c));
        }

        void main() {
            // Quarter-footprint offset in UV space: the four taps sit at the
            // centres of the destination pixel's four source-footprint quadrants.
            vec2 off = u_footprintTexels * 0.25 * u_srcTexel;

            vec4 s0 = texture2D(u_texture, v_texCoords + vec2(-off.x, -off.y));
            vec4 s1 = texture2D(u_texture, v_texCoords + vec2( off.x, -off.y));
            vec4 s2 = texture2D(u_texture, v_texCoords + vec2(-off.x,  off.y));
            vec4 s3 = texture2D(u_texture, v_texCoords + vec2( off.x,  off.y));

            // Average colour in linear light, weighted by alpha (premultiplied) so
            // a transparent texel contributes no colour; average alpha straight.
            vec3 lin = toLinear(s0.rgb) * s0.a
                     + toLinear(s1.rgb) * s1.a
                     + toLinear(s2.rgb) * s2.a
                     + toLinear(s3.rgb) * s3.a;
            float aSum = s0.a + s1.a + s2.a + s3.a;
            float aAvg = aSum * 0.25;

            vec3 rgb = aSum > 0.0 ? toSrgb(lin / aSum) : vec3(0.0);
            gl_FragColor = vec4(rgb, aAvg) * v_color;
        }
        """.trimIndent()
}
