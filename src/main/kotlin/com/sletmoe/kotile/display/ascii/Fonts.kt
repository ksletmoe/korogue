package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.tiles.TileSheet
import javafx.scene.image.Image

object Fonts {
    val CP437_10x10 = Font("cp437_10x10.png", 10, 10)
}

class Font(fontFileName: String, val charWidthPx: Int, val charHeightPx: Int) {
    private val tileSheet: TileSheet
    val glyphs: List<Image>

    init {
        val tileSheetInputStream = javaClass.getResourceAsStream("/$fontFileName")

        if (tileSheetInputStream != null) {
            tileSheet = TileSheet(tileSheetInputStream,  charWidthPx, charHeightPx)
        } else {
            throw IllegalArgumentException("Could not find font file $fontFileName")
        }

        this.glyphs = loadGlyphs(tileSheet)
    }

    companion object {
        private fun loadGlyphs(tileSheet: TileSheet): List<Image> {
            val glyphs = mutableListOf<Image>()

            repeat(256) { idx ->
                // font code pages are 16 by 16
                val tileX = idx % 16
                val tileY = idx / 16

                glyphs.add(tileSheet.getTileImageFromSheet(tileX, tileY))
            }

            return glyphs
        }
    }
}
