package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A bitmap font laid out as a 16x16 CP437 code page.
 *
 * [keyColor] is zeroed out to transparent so glyphs composite over a tile's
 * background color; pass `null` for a sheet that already has an alpha channel.
 */
class Font(
    fontFileName: String,
    val charWidthPx: Int,
    val charHeightPx: Int,
    keyColor: Color? = Color.BLACK,
) : Disposable {
    private val tileSheet = TileSheet(Gdx.files.classpath(fontFileName), charWidthPx, charHeightPx, keyColor)

    private val glyphs: List<TextureRegion> = (0 until GLYPH_COUNT).map { index ->
        tileSheet.region(index % COLUMNS, index / COLUMNS)
    }

    fun glyph(character: Char): TextureRegion? = glyphs.getOrNull(character.code)

    override fun dispose() = tileSheet.dispose()

    private companion object {
        const val GLYPH_COUNT = 256
        const val COLUMNS = 16
    }
}

object Fonts {
    fun cp437_10x10(keyColor: Color? = Color.BLACK): Font = Font("cp437_10x10.png", 10, 10, keyColor)
}
