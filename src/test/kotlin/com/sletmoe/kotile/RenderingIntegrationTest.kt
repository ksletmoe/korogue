package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
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
        // Tile (0,1) of the Vaarn sheet is the blue floor (~#316781).
        val avg = renderFloorTile(Color.WHITE)
        avg.b shouldBeGreaterThan avg.r
        avg.b.toDouble() shouldBe (0.5 plusOrMinus 0.2)
    }

    test("a sprite tile is recolored by its tint").config(enabled = HeadlessGl.available) {
        // The same blue floor tinted red multiplies down to a red-dominant color.
        val avg = renderFloorTile(Color.RED)
        avg.r shouldBeGreaterThan avg.b
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }
})

private fun renderFloorTile(tint: Color): Color {
    val pixels = HeadlessGl.render(64, 64, Color.BLACK) {
        val sheet = TileSheet(Gdx.files.classpath("vaarn-8x8.png"), 8, 8)
        val canvas = KotileCanvas(8, 8)
        val renderer = SpriteTileRenderer(canvas, sheet)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                renderer.drawTile(x, y, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 1, tint = tint))
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
