package com.sletmoe.kotile.utilities

import javafx.scene.image.Image
import javafx.scene.image.PixelFormat

fun Image.elementSizeBytes(): Int = when(this.pixelReader.pixelFormat.type) {
    PixelFormat.Type.BYTE_RGB -> 3
    PixelFormat.Type.BYTE_BGRA, PixelFormat.Type.BYTE_BGRA_PRE -> 4
    PixelFormat.Type.INT_ARGB, PixelFormat.Type.INT_ARGB_PRE -> 4
    else -> throw IllegalArgumentException("Unsupported pixel format: ${this.pixelReader.pixelFormat.type}")
}
