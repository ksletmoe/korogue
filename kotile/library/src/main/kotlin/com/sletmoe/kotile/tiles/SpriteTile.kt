package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * Anything that can be stored in a [com.sletmoe.kotile.utilities.LayeredTilemap]
 * and dispatched by [com.sletmoe.kotile.rendering.TileRenderer] as a sprite.
 *
 * Two branches, split by *who resolves the [TextureRegion]*:
 * - [StaticSpriteTile] — coordinate-based; the tile carries only sheet
 *   coordinates and the renderer resolves the region (specifically
 *   [com.sletmoe.kotile.rendering.SpriteTileRenderer], which looks it up in a
 *   [TileSheet]).
 * - [DynamicSpriteTile] — region-owning; the tile produces its own region for a
 *   given elapsed time, so its appearance can change frame to frame.
 *   [AnimatedSpriteTile] is the built-in implementation.
 *
 * This mirrors the ASCII path's [com.sletmoe.kotile.display.ascii.AsciiTile]
 * hierarchy one-for-one — same base/static/dynamic/animated roles, same names —
 * so intuition transfers between the two.
 */
public sealed interface SpriteTile
