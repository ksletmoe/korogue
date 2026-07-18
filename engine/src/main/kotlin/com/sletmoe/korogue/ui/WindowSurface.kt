package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile

/**
 * The real [TileSurface]: writes cells into a kotile [AsciiTileWindow]'s internal z-layered grid
 * via `drawTile`. The game clears the window, has the [UiRoot] render through this surface, then
 * calls `window.render(elapsedMs)` once to composite (ADR-0011). Thin and GL-bound (the window
 * owns GPU resources), so it carries no logic worth a headless test — the toolkit is tested
 * against a fake surface instead.
 */
class WindowSurface(
    private val window: AsciiTileWindow,
) : TileSurface {
    override val width: Int get() = window.widthInTiles
    override val height: Int get() = window.heightInTiles

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    ) {
        put(x, y, z, StaticAsciiTile(glyph, fg, bg))
    }

    /**
     * Hands the (possibly time-varying) [tile] straight to the window, which resolves it per frame
     * from its own clock (ADR-0033) — so a `DynamicAsciiTile` placed here actually animates rather
     * than freezing on its first frame. `drawTile` already accepts both tile branches.
     */
    override fun put(
        x: Int,
        y: Int,
        z: Int,
        tile: AsciiTile,
    ) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        window.drawTile(x, y, z, tile)
    }
}
