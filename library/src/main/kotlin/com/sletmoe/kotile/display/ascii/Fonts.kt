package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A bitmap font loaded from a classpath image laid out as a 16x16 CP437 code
 * page (256 glyphs). Owns a GPU texture and must be [dispose]d.
 *
 * @param fontFileName classpath path of the font sheet image
 * @param keyColor color zeroed out to transparent so glyphs composite over a
 *   cell's background; pass `null` for a sheet that already has an alpha channel
 * @property charWidthPx width of a single glyph, in pixels
 * @property charHeightPx height of a single glyph, in pixels
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

    /**
     * Returns the glyph region for [character], or `null` if its code point is
     * outside the 256-glyph code page.
     */
    fun glyph(character: Char): TextureRegion? = glyphs.getOrNull(character.code)

    /** Disposes the underlying glyph sheet. */
    override fun dispose() = tileSheet.dispose()

    private companion object {
        const val GLYPH_COUNT = 256
        const val COLUMNS = 16
    }
}

/** Built-in [Font]s bundled with the library. */
object Fonts {
    /** The bundled 10x10 CP437 font. [keyColor] (default black) is made transparent. */
    fun cp437_10x10(keyColor: Color? = Color.BLACK): Font = Font("cp437_10x10.png", 10, 10, keyColor)
}
