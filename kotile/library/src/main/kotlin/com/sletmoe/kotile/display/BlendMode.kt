package com.sletmoe.kotile.display

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch

/**
 * How [KotileCanvas.drawSprite] combines drawn pixels with what's already on screen.
 */
enum class BlendMode {
    /** Standard alpha compositing — drawn pixels replace/blend over what's beneath. The default. */
    NORMAL,

    /**
     * Drawn pixels are added to what's beneath instead of composited over it — the standard way
     * to brighten a sprite (a hit-flash, a glow) toward white, which a multiply [Color][com.badlogic.gdx.graphics.Color]
     * tint cannot do (a tint only ever multiplies the texture's own pixel values, so a dark pixel
     * stays dark regardless of the tint's magnitude; light is genuinely additive, recoloring isn't).
     */
    ADDITIVE,

    /**
     * Drawn pixels overwrite what's beneath outright — color and alpha alike — instead of
     * compositing over it. Needed when the source already **is** the complete, authoritative
     * image for the region it covers (e.g. blitting a [com.sletmoe.kotile.rendering.GridCompositeCache]'s
     * offscreen composite onto the canvas, krogue-drk/ADR-0024): with [NORMAL] blending, a
     * transparent source pixel (`alpha == 0`, meaning "nothing here now") leaves the destination's
     * *previous* frame content untouched instead of clearing it, so a cell cleared since the last
     * paint would keep showing its stale color forever.
     */
    REPLACE,
    ;

    /**
     * Sets [batch]'s GL blend function to match this mode. [SpriteBatch.setBlendFunctionSeparate]
     * already no-ops (and otherwise flushes) internally when the function is unchanged, so this is
     * safe to call before every draw regardless of the previous mode.
     */
    internal fun apply(batch: SpriteBatch) {
        when (this) {
            NORMAL ->
                batch.setBlendFunctionSeparate(
                    GL20.GL_SRC_ALPHA,
                    GL20.GL_ONE_MINUS_SRC_ALPHA,
                    GL20.GL_SRC_ALPHA,
                    GL20.GL_ONE_MINUS_SRC_ALPHA,
                )
            ADDITIVE -> batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE, GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            REPLACE -> batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ZERO, GL20.GL_ONE, GL20.GL_ZERO)
        }
    }
}
