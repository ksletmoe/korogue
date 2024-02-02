package com.sletmoe.kotile.tiles

import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import java.io.InputStream

class TileSheet(
    tileSheetImageInputStream: InputStream,
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    private val keyColor: Color? = null,
) {
    private val tileSheetImage = Image(tileSheetImageInputStream)

    private val widthInTiles = tileSheetImage.width / tileWidthPx
    private val heightInTiles = tileSheetImage.height / tileHeightPx

    fun getTileImageFromSheet(x: Int, y: Int): Image {
        if (x < 0 || x >= widthInTiles) {
            throw IllegalArgumentException("x must be between 0 and $widthInTiles")
        }

        if (y < 0 || y >= heightInTiles) {
            throw IllegalArgumentException("y must be between 0 and $heightInTiles")
        }

        val tileImage = WritableImage(tileWidthPx, tileHeightPx)
        val tileSheetPixelReader = tileSheetImage.pixelReader
        val pixelWriter = tileImage.pixelWriter

        repeat(tileWidthPx) { pixelX ->
            repeat(tileHeightPx) { pixelY ->
                val pixel = tileSheetPixelReader.getColor(x * tileWidthPx + pixelX, y * tileHeightPx + pixelY)

                if (keyColor != null && pixel == keyColor) {
                    pixelWriter.setColor(pixelX, pixelY, Color.TRANSPARENT)
                } else {
                    pixelWriter.setColor(pixelX, pixelY, pixel)
                }
            }
        }

        return tileImage
    }
}
