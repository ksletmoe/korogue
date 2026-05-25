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
 */
class TileSheet(
    file: FileHandle,
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    keyColor: Color? = null,
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

        widthInTiles = pixmap.width / tileWidthPx
        heightInTiles = pixmap.height / tileHeightPx

        pixmap.dispose()
    }

    fun region(x: Int, y: Int): TextureRegion {
        require(x in 0 until widthInTiles) { "x must be in 0 until $widthInTiles, was $x" }
        require(y in 0 until heightInTiles) { "y must be in 0 until $heightInTiles, was $y" }

        return TextureRegion(texture, x * tileWidthPx, y * tileHeightPx, tileWidthPx, tileHeightPx)
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
