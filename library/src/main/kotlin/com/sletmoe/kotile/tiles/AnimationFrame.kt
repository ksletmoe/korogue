package com.sletmoe.kotile.tiles

/**
 * A single frame in an animation sequence.
 *
 * @param T the content type of this frame — either a
 *   [com.badlogic.gdx.graphics.g2d.TextureRegion] (for sprite tiles) or an
 *   [com.sletmoe.kotile.display.ascii.AsciiTileDescriptor] (for ASCII tiles)
 * @property content the visual content to display while this frame is active
 * @property durationMs how long this frame is displayed, in milliseconds. Must
 *   be positive; zero-duration frames are illegal and cause an
 *   [IllegalArgumentException] at animation construction time.
 */
public data class AnimationFrame<T>(
    val content: T,
    val durationMs: Long,
)
