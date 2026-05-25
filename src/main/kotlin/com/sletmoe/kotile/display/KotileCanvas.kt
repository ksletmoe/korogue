package com.sletmoe.kotile.display

import com.sletmoe.kotile.utilities.ResizableCanvas
import javafx.scene.Node
import javafx.scene.image.Image
import kotlin.math.floor

class KotileCanvas private constructor(
    val tileWidthPx: Int,
    val tileHeightPx: Int,
) {
    private val canvas = ResizableCanvas()
    private val graphicsContext = canvas.graphicsContext2D

    val node: Node
        get() = canvas

    var widthPx: Double
        get() = canvas.width
        set(value) {
            canvas.width = value
        }

    var heightPx: Double
        get() = canvas.height
        set(value) {
            canvas.height = value
        }

    val width: Int
        get() = floor(widthPx / tileWidthPx).toInt()

    val height: Int
        get() = floor(heightPx / tileHeightPx).toInt()

    fun drawTile(x: Int, y: Int, tile: Image) {
        graphicsContext.drawImage(tile, x * tileWidthPx.toDouble(), y * tileHeightPx.toDouble())
    }

    fun fill(tile: Image) {
        repeat(width) { x ->
            repeat(height) { y ->
                drawTile(x, y, tile)
            }
        }
    }

    // TODO: clearRect or draw blank tile?
    fun clear() {
        graphicsContext.clearRect(0.0, 0.0, widthPx, heightPx)
    }

    fun clearTile(x: Int, y: Int) {
        graphicsContext.clearRect(
            x * tileWidthPx.toDouble(),
            y * tileHeightPx.toDouble(),
            tileWidthPx.toDouble(),
            tileHeightPx.toDouble(),
        )
    }

    companion object {
        fun create(init: KotileCanvasConfig.() -> Unit): KotileCanvas {
            val config = KotileCanvasConfig().apply(init)
            return KotileCanvas(
                config.tileWidthPx,
                config.tileHeightPx,
            )
        }
    }
}

data class KotileCanvasConfig(
    var tileWidthPx: Int = 10,
    var tileHeightPx: Int = 10,
)


