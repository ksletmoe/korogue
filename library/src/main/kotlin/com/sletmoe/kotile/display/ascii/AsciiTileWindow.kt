package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.utilities.Grid

/**
 * A grid of ASCII cells rendered with a bitmap [Font].
 *
 * Cell state is set with [drawTile], [drawText], and [fill], then drawn by
 * [render], which redraws every populated cell each frame: a background quad
 * tinted by the cell's background color, then the glyph tinted by its
 * foreground color. Create instances with [create]. Owns GPU resources and must
 * be [dispose]d.
 *
 * @property widthInTiles grid width in cells
 * @property heightInTiles grid height in cells
 */
class AsciiTileWindow private constructor(
    private val font: Font,
    private val canvas: KotileCanvas,
    val widthInTiles: Int,
    val heightInTiles: Int,
) : Disposable {
    private val tiles = Grid<AsciiTileDescriptor?>(widthInTiles, heightInTiles, null)

    private val backgroundTexture: Texture
    private val backgroundRegion: TextureRegion

    init {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        backgroundTexture = Texture(pixmap)
        backgroundRegion = TextureRegion(backgroundTexture)
        pixmap.dispose()
    }

    /**
     * Sets the cell at column [x], row [y] to [tile].
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    fun drawTile(x: Int, y: Int, tile: AsciiTileDescriptor) {
        tiles[x, y] = tile
    }

    /**
     * Writes [text] starting at ([x], [y]), one character per cell to the
     * right, in [foreground] over [background]. Characters that fall outside
     * the window are skipped rather than throwing.
     */
    fun drawText(
        x: Int,
        y: Int,
        text: String,
        foreground: Color = Color.WHITE,
        background: Color = Color.BLACK,
    ) {
        if (y !in 0 until heightInTiles) return

        text.forEachIndexed { index, character ->
            val cellX = x + index
            if (cellX in 0 until widthInTiles) {
                tiles[cellX, y] = AsciiTileDescriptor(character, foreground, background)
            }
        }
    }

    /** Sets every cell to [tile]. */
    fun fill(tile: AsciiTileDescriptor) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                tiles[x, y] = tile
            }
        }
    }

    /** Clears the cell at column [x], row [y] so nothing is drawn there. */
    fun clearTile(x: Int, y: Int) {
        tiles[x, y] = null
    }

    /** Clears every cell. */
    fun clear() = tiles.clear()

    /** Draws all populated cells to the canvas for this frame. */
    fun render() {
        canvas.begin()
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                val tile = tiles[x, y] ?: continue

                canvas.drawTile(x, y, backgroundRegion, tile.backgroundColor)
                font.glyph(tile.character)?.let { glyph ->
                    canvas.drawTile(x, y, glyph, tile.foregroundColor)
                }
            }
        }
        canvas.end()
    }

    /** Forwards a viewport resize to the underlying canvas. */
    fun resize(widthPx: Int, heightPx: Int) = canvas.resize(widthPx, heightPx)

    /** Disposes the canvas, font, and background texture. */
    override fun dispose() {
        canvas.dispose()
        font.dispose()
        backgroundTexture.dispose()
    }

    /** Factory for building [AsciiTileWindow] instances. */
    companion object {
        /**
         * Builds an [AsciiTileWindow] from an [AsciiTileWindowConfig]. The
         * canvas tile size is taken from the font.
         *
         * ```
         * val window = AsciiTileWindow.create {
         *     widthInTiles = 80
         *     heightInTiles = 30
         * }
         * ```
         */
        fun create(init: AsciiTileWindowConfig.() -> Unit): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            val font = config.font ?: Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)

            return AsciiTileWindow(font, canvas, config.widthInTiles, config.heightInTiles)
        }
    }
}

/**
 * Configuration for [AsciiTileWindow.create].
 *
 * @property font font to render with; defaults to [Fonts.cp437_10x10] when null
 * @property widthInTiles grid width in cells
 * @property heightInTiles grid height in cells
 */
data class AsciiTileWindowConfig(
    var font: Font? = null,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
)
