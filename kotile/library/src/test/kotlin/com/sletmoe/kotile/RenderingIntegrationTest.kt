package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests that drive real OpenGL rendering through the public API
 * and assert on the produced pixels. Skipped unless a (software) GL context
 * is available; see [HeadlessGl].
 */
class RenderingIntegrationTest : FunSpec({

    test("the headless GL harness renders a solid clear color").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(32, 32, Color.BLUE) { }
        val avg = pixels.averageColor(0, 0, 32, 32)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.b.toDouble() shouldBe (1.0 plusOrMinus 0.05)
        pixels.dispose()
    }

    test("AsciiTileWindow fills cells with the background color").config(enabled = HeadlessGl.available) {
        // A space glyph is fully keyed-out to transparent, so only the
        // background quad (white tinted by the background color) shows.
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 8
                heightInTiles = 4
            }
            window.fill(AsciiTileDescriptor(' ', Color.WHITE, Color.BLUE))
            window.render()
            window.dispose()
        }
        val avg = pixels.averageColor(0, 0, 80, 40)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("a glyph is foreground-tinted and drawn at the top-left origin").config(enabled = HeadlessGl.available) {
        // Full block (CP437 0xDB) is solid white; tinted red it fills cell
        // (0,0). Other cells stay at the black clear color.
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 8
                heightInTiles = 4
            }
            window.drawTile(0, 0, AsciiTileDescriptor('Û', Color.RED, Color.CLEAR))
            window.render()
            window.dispose()
        }

        val topLeft = pixels.averageColor(0, 0, 10, 10)
        topLeft.r.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        topLeft.g.toDouble() shouldBe (0.0 plusOrMinus 0.15)
        topLeft.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)

        val untouched = pixels.averageColor(70, 30, 80, 40)
        untouched.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        untouched.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        untouched.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
    }

    test("SpriteTileRenderer draws an image sheet tile in its own colors").config(enabled = HeadlessGl.available) {
        // A synthetic sheet whose only tile is blue; drawn untinted it keeps it.
        val avg = renderSpriteTile(tileColor = SHEET_BLUE, tint = Color.WHITE)
        avg.b shouldBeGreaterThan avg.r
        avg.b.toDouble() shouldBe (0.9 plusOrMinus 0.1)
    }

    test("a sprite tile is recolored by its tint").config(enabled = HeadlessGl.available) {
        // The same blue tile tinted red multiplies down to a red-dominant color.
        val avg = renderSpriteTile(tileColor = SHEET_BLUE, tint = Color.RED)
        avg.r shouldBeGreaterThan avg.b
        avg.r.toDouble() shouldBe (0.3 plusOrMinus 0.1)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }

    test("TileSheet honors margin and spacing").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(8, 4, Color.BLACK) {
            // A 2x1 sheet of 4x4 tiles: 1px border, 2px gap between tiles.
            // Width = 2*margin + 2*tile + 1*spacing = 2 + 8 + 2 = 12; height = 6.
            val sheetPixmap = Pixmap(12, 6, Pixmap.Format.RGBA8888)
            sheetPixmap.setColor(Color.BLACK)
            sheetPixmap.fill()
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(1, 1, 4, 4) // tile (0,0)
            sheetPixmap.setColor(Color.GREEN)
            sheetPixmap.fillRectangle(7, 1, 4, 4) // tile (1,0): 1 + (4 + 2)
            val file = File.createTempFile("kotile-sheet", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
            sheetPixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 4, 4, margin = 1, spacing = 2)
            sheet.widthInTiles shouldBe 2
            sheet.heightInTiles shouldBe 1

            val canvas = KotileCanvas(4, 4)
            canvas.begin()
            canvas.drawTile(0, 0, sheet.region(0, 0))
            canvas.drawTile(1, 0, sheet.region(1, 0))
            canvas.end()
            canvas.dispose()
            sheet.dispose()
        }

        val left = pixels.averageColor(0, 0, 4, 4)
        left.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        left.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        val right = pixels.averageColor(4, 0, 8, 4)
        right.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        right.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
    }

    test("AsciiTileWindow.drawText renders glyphs and clips to the window").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 8
                heightInTiles = 4
            }
            // Starts at column 6 in an 8-wide window: only 2 cells fit, the
            // rest must be clipped rather than throwing.
            window.drawText(6, 0, "HELLO", Color.RED, Color.CLEAR)
            window.render()
            window.dispose()
        }

        // Cell (6,0) holds a red glyph; cell (0,0) was never written.
        pixels.averageColor(60, 0, 70, 10).r.toDouble() shouldBeGreaterThan 0.05
        pixels.averageColor(0, 0, 10, 10).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        pixels.dispose()
    }
})

private val SHEET_BLUE = Color(0.3f, 0.3f, 0.9f, 1f)

private fun renderSpriteTile(tileColor: Color, tint: Color): Color {
    val pixels = HeadlessGl.render(64, 64, Color.BLACK) {
        val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        tilePixmap.setColor(tileColor)
        tilePixmap.fill()
        val file = File.createTempFile("kotile-sprite", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
        tilePixmap.dispose()

        val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
        val canvas = KotileCanvas(8, 8)
        val renderer = SpriteTileRenderer(canvas, sheet)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                renderer.drawTile(x, y, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 0, tint = tint))
            }
        }
        renderer.render()
        canvas.dispose()
        sheet.dispose()
    }
    val avg = pixels.averageColor(0, 0, 64, 64)
    pixels.dispose()
    return avg
}
