package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.utilities.CacheConfig
import javafx.fxml.Initializable
import java.net.URL
import java.util.ResourceBundle

class AsciiTileWindow private constructor(private val renderer: AsciiTileRenderer, private val canvas: KotileCanvas) : Initializable {
    override fun initialize(location: URL, resources: ResourceBundle) {

    }

    suspend fun drawTile(x: Int, y: Int, tile: AsciiTileDescriptor) {
        canvas.drawTile(x, y, renderer.renderGlyph(tile))
    }

    suspend fun fill(tile: AsciiTileDescriptor) {
        canvas.fill(renderer.renderGlyph(tile))
    }

    companion object {
        fun create(init: AsciiTileWindowConfig.() -> Unit): AsciiTileWindow {
            val config = AsciiTileWindowConfig()
            config.init()
            val renderer = AsciiTileRenderer(config.font, config.textureCacheConfig)
            val canvas = KotileCanvas.create {
                tileWidthPx = config.font.charWidthPx
                tileHeightPx = config.font.charHeightPx
            }

            return AsciiTileWindow(renderer, canvas)
        }
    }

}

data class AsciiTileWindowConfig(
    var font: Font = Fonts.CP437_10x10,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
    var textureCacheConfig: CacheConfig = CacheConfig(),
)
