package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color

/**
 * A single frame in an animation sequence.
 *
 * @param T the content type of this frame — either a
 *   [com.badlogic.gdx.graphics.g2d.TextureRegion] (for sprite tiles) or a
 *   [com.sletmoe.kotile.display.ascii.StaticAsciiTile] (for ASCII tiles)
 * @property content the visual content to display while this frame is active
 * @property durationMs how long this frame is displayed, in milliseconds. Must
 *   be positive; zero-duration frames are illegal and cause an
 *   [IllegalArgumentException] at animation construction time.
 * @property tint per-frame tint override (krogue-2ur), meaningful only for
 *   [AnimatedSpriteTile] frames (a [com.sletmoe.kotile.display.ascii.StaticAsciiTile]
 *   already carries its own per-frame color, so ASCII frames leave this `null`). `null`
 *   (the default) means "use the tile's constant [DynamicSpriteTile.tint]" — see
 *   [AnimatedSpriteTile.tintFor].
 */
public data class AnimationFrame<T>(
    val content: T,
    val durationMs: Long,
    val tint: Color? = null,
)
