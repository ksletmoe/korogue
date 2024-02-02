package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.utilities.CacheConfig
import com.sletmoe.kotile.utilities.ColorExtensions.alphaBlend
import com.sletmoe.kotile.utilities.ConcreteTextureCache
import com.sletmoe.kotile.utilities.NullTextureCache
import com.sletmoe.kotile.utilities.ColorExtensions.times
import javafx.scene.image.Image
import javafx.scene.image.WritableImage

class AsciiTileRenderer(private val font: Font, textureCacheConfig: CacheConfig? = null) {
    private val textureCache = if (textureCacheConfig != null) {
        ConcreteTextureCache(textureCacheConfig)
    } else {
        NullTextureCache()
    }

    suspend fun renderGlyph(asciiTileDescriptor: AsciiTileDescriptor): Image {
        return textureCache.getTexture(buildCacheKey(asciiTileDescriptor)) {
            val glyph = font.glyphs[asciiTileDescriptor.character.code]
            val glyphPixelReader = glyph.pixelReader
            val tileImage = WritableImage(glyph.width.toInt(), glyph.height.toInt())
            val tileImagePixelWriter = tileImage.pixelWriter

            repeat(glyph.width.toInt()) { x ->
                repeat(glyph.height.toInt()) { y ->
                    val color = glyphPixelReader.getColor(x, y) * asciiTileDescriptor.foregroundColor
                    tileImagePixelWriter.setColor(x, y, color.alphaBlend(asciiTileDescriptor.backgroundColor))
                }
            }

            tileImage
        }
    }

    private fun buildCacheKey(asciiTileDescriptor: AsciiTileDescriptor): Int {
        return "${asciiTileDescriptor.character}-${asciiTileDescriptor.foregroundColor}-${asciiTileDescriptor.backgroundColor}".toInt()
    }
}
