package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * Marker interface for any tile type that can be stored in a
 * [com.sletmoe.kotile.utilities.LayeredTilemap] and dispatched by
 * [com.sletmoe.kotile.rendering.TileRenderer].
 *
 * There are two concrete branches:
 * - [StaticTile] — coordinate-based; region resolution is delegated to the
 *   renderer (specifically [com.sletmoe.kotile.rendering.SpriteTileRenderer]
 *   which looks up the [TextureRegion] from a [TileSheet]).
 * - [Tile] — region-owning; the tile itself can produce its current
 *   [TextureRegion] given an elapsed-time value, enabling animation.
 */
public sealed interface SpriteTileEntry

/**
 * A time-driven, region-owning tile that can be placed in a
 * [com.sletmoe.kotile.utilities.LayeredTilemap] and rendered by a
 * [com.sletmoe.kotile.rendering.TileRenderer].
 *
 * The time model is **stateless**: callers supply the elapsed wall-clock time
 * in milliseconds on every [regionFor] call. This keeps tile instances cheap
 * and shareable — the same [Tile] object can appear at many grid cells without
 * per-cell state. Animation is therefore wall-clock-synced: every cell holding
 * the same tile object shows the same frame at the same moment.
 *
 * If per-instance animation offsets are needed (e.g. "this cell started
 * playing 200 ms later than that one") the consumer can wrap the [Tile] and
 * subtract the desired offset before calling [regionFor].
 */
public interface Tile : SpriteTileEntry {
    /**
     * The color multiplied with this tile's pixels at draw time. [Color.WHITE]
     * leaves the sprite unchanged.
     */
    public val tint: Color

    /**
     * Returns the [TextureRegion] that should be drawn for this tile at the
     * given wall-clock time.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds.
     *   Passing `0` always returns the first (or only) frame.
     */
    public fun regionFor(elapsedMs: Long): TextureRegion
}
