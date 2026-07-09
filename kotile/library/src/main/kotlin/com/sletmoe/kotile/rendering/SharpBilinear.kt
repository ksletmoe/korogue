package com.sletmoe.kotile.rendering

/**
 * GLSL sources for the **sharp-bilinear** filter used to smooth *fractional*
 * tile scaling without the mushy blur a plain bilinear stretch produces.
 *
 * ## What it does
 *
 * At an integer scale, nearest-neighbour keeps bitmap glyphs and sprites crisp.
 * At a fractional scale (the [FitScale] path) nearest-neighbour instead drops or
 * duplicates whole source texels unevenly, so glyph strokes shimmer and change
 * width across the grid. Sharp-bilinear is the emulator trick that fixes this:
 * it snaps sampling to the centre of each source texel across the texel's
 * interior and only lets the GPU's bilinear unit blend within a **one-screen-
 * pixel-wide band** at the texel boundary. The texel interiors stay crisp; only
 * the edges get a minimal anti-aliasing blend sized to the on-screen scale.
 *
 * This is a single-pass fragment shader bound on the shared
 * [com.sletmoe.kotile.display.KotileCanvas] batch, so it applies uniformly to
 * **both** the glyph layer and the sprite-tile layer — anything drawn through
 * the canvas. It requires the sampled texture to use a `Linear` mag filter (the
 * canvas switches the filter on while the shader is active and restores
 * `Nearest` afterwards) so `texture2D` performs the hardware interpolation the
 * shader relies on.
 *
 * The lighter-weight counterpart to the supersample→FBO policy (krogue-1zo);
 * see `docs/adr/0017-display-scaling-and-resize.md` and krogue-m2x.
 *
 * ## Uniforms
 *
 * - `u_texture` — the bound atlas/sheet (unit 0), as for the default batch shader.
 * - `u_textureSize` — the bound texture's size in texels; set whenever the bound
 *   texture changes so the shader can locate texel boundaries in UV space.
 * - `u_scale` — on-screen pixels per source texel, per axis (i.e. the fractional
 *   tile scale). Governs how wide the edge-blend band is.
 *
 * The algorithm is the well-known "sharp bilinear" by Themaister / RetroArch.
 */
internal object SharpBilinear {
    /** Standard libGDX SpriteBatch vertex shader (kept so the default vertex path is unchanged). */
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

    /** Sharp-bilinear fragment shader. Requires a `Linear` mag filter on `u_texture`. */
    val FRAGMENT: String =
        """
        #ifdef GL_ES
        precision mediump float;
        #endif

        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform vec2 u_textureSize;
        uniform vec2 u_scale;

        void main() {
            // Position within the source texture measured in texels.
            vec2 texel = v_texCoords * u_textureSize;
            vec2 texelFloored = floor(texel);
            vec2 subpixel = fract(texel);

            // Keep the texel interior crisp; only blend within a 1/scale band at
            // the boundary. region_range is the half-width of the crisp interior.
            vec2 regionRange = 0.5 - 0.5 / u_scale;
            vec2 centerDist = subpixel - 0.5;
            vec2 f = (centerDist - clamp(centerDist, -regionRange, regionRange)) * u_scale + 0.5;

            vec2 modTexel = texelFloored + f;
            gl_FragColor = v_color * texture2D(u_texture, modTexel / u_textureSize);
        }
        """.trimIndent()
}
