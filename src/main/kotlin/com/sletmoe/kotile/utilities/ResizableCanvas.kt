package com.sletmoe.kotile.utilities

import javafx.scene.canvas.Canvas

// https://stackoverflow.com/a/57154987/906751
class ResizableCanvas : Canvas() {
    override fun isResizable(): Boolean = true
    override fun minWidth(height: Double): Double = 1.0
    override fun minHeight(width: Double): Double = 1.0
    override fun maxWidth(height: Double): Double = Double.MAX_VALUE
    override fun maxHeight(width: Double): Double = Double.MAX_VALUE
    override fun resize(width: Double, height: Double) {
        this.width = width
        this.height = height

        graphicsContext2D.clearRect(0.0, 0.0, width, height)
    }
}
