package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.utilities.Grid

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

    fun drawTile(x: Int, y: Int, tile: AsciiTileDescriptor) {
        tiles[x, y] = tile
    }

    fun fill(tile: AsciiTileDescriptor) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                tiles[x, y] = tile
            }
        }
    }

    fun clearTile(x: Int, y: Int) {
        tiles[x, y] = null
    }

    fun clear() = tiles.clear()

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

    fun resize(widthPx: Int, heightPx: Int) = canvas.resize(widthPx, heightPx)

    override fun dispose() {
        canvas.dispose()
        font.dispose()
        backgroundTexture.dispose()
    }

    companion object {
        fun create(init: AsciiTileWindowConfig.() -> Unit): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            val font = config.font ?: Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)

            return AsciiTileWindow(font, canvas, config.widthInTiles, config.heightInTiles)
        }
    }
}

data class AsciiTileWindowConfig(
    var font: Font? = null,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
)
