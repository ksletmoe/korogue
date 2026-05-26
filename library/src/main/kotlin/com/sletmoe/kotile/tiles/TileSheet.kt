package com.sletmoe.kotile.tiles

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/**
 * Slices an image into a grid of equally sized tiles.
 *
 * [keyColor], when provided, is zeroed out to fully transparent on load, so a
 * sheet authored with a solid background (e.g. magenta or black) can be used
 * with alpha blending. Pass `null` to keep the image untouched.
 *
 * [margin] is the empty border (in pixels) around the whole sheet and
 * [spacing] is the gap between adjacent tiles, matching the convention used by
 * editors such as Tiled. Both default to 0 for tightly packed sheets.
 */
class TileSheet(
    file: FileHandle,
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    keyColor: Color? = null,
    private val margin: Int = 0,
    private val spacing: Int = 0,
) : Disposable {
    private val texture: Texture

    val widthInTiles: Int
    val heightInTiles: Int

    init {
        val pixmap = Pixmap(file)
        if (keyColor != null) {
            zeroOutColor(pixmap, keyColor)
        }

        texture = Texture(pixmap)
        texture.setFilter(TextureFilter.Nearest, TextureFilter.Nearest)

        widthInTiles = tilesAlong(pixmap.width, tileWidthPx)
        heightInTiles = tilesAlong(pixmap.height, tileHeightPx)

        pixmap.dispose()
    }

    private fun tilesAlong(imagePx: Int, tilePx: Int): Int {
        val usable = imagePx - 2 * margin + spacing
        return if (usable > 0) usable / (tilePx + spacing) else 0
    }

    fun region(x: Int, y: Int): TextureRegion {
        require(x in 0 until widthInTiles) { "x must be in 0 until $widthInTiles, was $x" }
        require(y in 0 until heightInTiles) { "y must be in 0 until $heightInTiles, was $y" }

        val px = margin + x * (tileWidthPx + spacing)
        val py = margin + y * (tileHeightPx + spacing)
        return TextureRegion(texture, px, py, tileWidthPx, tileHeightPx)
    }

    override fun dispose() = texture.dispose()

    private companion object {
        fun zeroOutColor(pixmap: Pixmap, keyColor: Color) {
            val keyRgb = Color.rgba8888(keyColor) ushr 8
            pixmap.blending = Pixmap.Blending.None

            for (y in 0 until pixmap.height) {
                for (x in 0 until pixmap.width) {
                    if (pixmap.getPixel(x, y) ushr 8 == keyRgb) {
                        pixmap.drawPixel(x, y, 0)
                    }
                }
            }
        }
    }
}
