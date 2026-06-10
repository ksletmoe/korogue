package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.tiles.TileSheet

/**
 * A bitmap font loaded from a classpath image laid out as a 16x16 CP437 code
 * page (256 glyphs in row-major order, code point 0 in the top-left cell).
 * Owns a GPU texture and must be [dispose]d when no longer needed.
 *
 * ## Using a bundled font
 *
 * The library ships with a 10x10 CP437 sheet. Use [Fonts.cp437_10x10] to get
 * a ready-to-use instance:
 *
 * ```kotlin
 * val font = Fonts.cp437_10x10()
 * ```
 *
 * ## Supplying a custom font
 *
 * Any CP437-layout sheet can be loaded by placing it on the classpath and
 * constructing a [Font] directly. For a Gradle/Maven project, put the file
 * under `src/main/resources` (or your test resources) and pass its path
 * relative to the classpath root:
 *
 * ```kotlin
 * // src/main/resources/fonts/cp437_12x12.png, 12x12 pixels per glyph,
 * // black background keyed to transparent.
 * val font = Font("fonts/cp437_12x12.png", charWidthPx = 12, charHeightPx = 12)
 *
 * val window = AsciiTileWindow.create {
 *     this.font = font
 *     widthInTiles = 80
 *     heightInTiles = 25
 * }
 * ```
 *
 * If your sheet already has an alpha channel (no key color needed), pass
 * `keyColor = null`:
 *
 * ```kotlin
 * val font = Font("fonts/my_font_alpha.png", charWidthPx = 8, charHeightPx = 16, keyColor = null)
 * ```
 *
 * @param fontFileName classpath-relative path to the font sheet image (e.g.
 *   `"fonts/cp437_12x12.png"`). Resolved via `Gdx.files.classpath(...)` at
 *   construction time.
 * @param charWidthPx width of a single glyph cell in the sheet, in pixels
 * @param charHeightPx height of a single glyph cell in the sheet, in pixels
 * @param keyColor a solid color in the sheet that will be made fully transparent
 *   so glyphs composite cleanly over a cell's background color. Defaults to
 *   [Color.BLACK]. Pass `null` if the sheet already carries an alpha channel.
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

/**
 * Built-in [Font]s bundled with the library.
 *
 * This object provides factory functions for the font sheets that ship inside
 * the kotile JAR. Each function allocates a GPU texture and returns a [Font]
 * that the caller is responsible for [Font.dispose]ing.
 *
 * ## Bundled fonts
 *
 * - [cp437_10x10] — a 10×10 pixel CP437 sheet (the only bundled font at this
 *   time).
 *
 * ## Custom fonts
 *
 * To use a font not listed here, construct a [Font] directly with the
 * classpath-relative path to your sheet and its glyph dimensions. See the
 * [Font] class documentation for a full example.
 */
object Fonts {
    /**
     * Returns the bundled 10×10 pixel CP437 bitmap font.
     *
     * [keyColor] (default [Color.BLACK]) is made fully transparent so glyphs
     * composite over a cell's background color. Pass `null` if you want to
     * preserve the original sheet pixels without keying.
     *
     * The caller owns the returned [Font] and must [Font.dispose] it when done.
     *
     * @param keyColor the solid color in the sheet to treat as transparent;
     *   defaults to [Color.BLACK]
     */
    fun cp437_10x10(keyColor: Color? = Color.BLACK): Font = Font("cp437_10x10.png", 10, 10, keyColor)
}
