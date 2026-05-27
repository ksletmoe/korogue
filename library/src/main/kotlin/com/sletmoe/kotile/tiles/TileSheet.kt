package com.sletmoe.kotile.tiles

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import java.nio.ByteBuffer

/**
 * Slices an image into a grid of equally sized tiles, uploaded to the GPU with
 * nearest-neighbour filtering so pixel art stays crisp when scaled. Owns the
 * backing texture and must be [dispose]d.
 *
 * @param file the image to load (e.g. `Gdx.files.classpath("sheet.png")`)
 * @param keyColor if non-null, every pixel of exactly this color is made fully
 *   transparent on load, so a sheet authored with a solid background (e.g.
 *   magenta or black) can be alpha-blended. Pass `null` to keep the image as-is.
 * @param margin empty border, in pixels, around the whole sheet (Tiled-style)
 * @param spacing gap, in pixels, between adjacent tiles (Tiled-style)
 * @property tileWidthPx width of a single tile, in pixels
 * @property tileHeightPx height of a single tile, in pixels
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

    /** Number of whole tiles across the sheet. */
    val widthInTiles: Int

    /** Number of whole tiles down the sheet. */
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

    /**
     * Returns the region for the tile at column [x], row [y].
     *
     * @throws IllegalArgumentException if [x] or [y] is outside the sheet
     */
    fun region(x: Int, y: Int): TextureRegion {
        require(x in 0 until widthInTiles) { "x must be in 0 until $widthInTiles, was $x" }
        require(y in 0 until heightInTiles) { "y must be in 0 until $heightInTiles, was $y" }

        val px = margin + x * (tileWidthPx + spacing)
        val py = margin + y * (tileHeightPx + spacing)
        return TextureRegion(texture, px, py, tileWidthPx, tileHeightPx)
    }

    /** Disposes the backing texture. */
    override fun dispose() = texture.dispose()

    private companion object {
        /**
         * Zeroes every pixel whose RGB components match [keyColor] by iterating
         * the [Pixmap]'s backing [ByteBuffer] directly. This avoids one
         * per-pixel JNI round-trip (getPixel/drawPixel) and instead touches
         * native memory sequentially in a single Java loop.
         *
         * Assumes RGBA8888 layout (4 bytes per pixel: R, G, B, A). If the
         * pixmap uses a different format, the RGB comparison may be incorrect;
         * Pixmap(FileHandle) always decodes to RGBA8888 on the desktop backend
         * so this assumption holds for normal sheet loading.
         */
        fun zeroOutColor(pixmap: Pixmap, keyColor: Color) {
            val keyR = (Color.rgba8888(keyColor) ushr 24 and 0xff).toByte()
            val keyG = (Color.rgba8888(keyColor) ushr 16 and 0xff).toByte()
            val keyB = (Color.rgba8888(keyColor) ushr  8 and 0xff).toByte()

            pixmap.blending = Pixmap.Blending.None
            val buf: ByteBuffer = pixmap.pixels
            buf.rewind()

            while (buf.remaining() >= 4) {
                val r = buf.get()
                val g = buf.get()
                val b = buf.get()
                buf.get() // alpha — read past it

                if (r == keyR && g == keyG && b == keyB) {
                    // Rewind 4 bytes and overwrite with transparent black.
                    buf.position(buf.position() - 4)
                    buf.put(0)
                    buf.put(0)
                    buf.put(0)
                    buf.put(0)
                }
            }
        }
    }
}
