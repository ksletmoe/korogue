package com.sletmoe.kotile.tiles

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.HeadlessGl
import com.sletmoe.kotile.averageColor
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Regression guard for the macOS NPOT-atlas bug: Apple's GL driver mishandles
 * sampling sub-regions of a non-power-of-two texture, which garbled glyphs and
 * sprites sliced from an NPOT sheet. [TileSheet] fixes this by padding the sheet
 * up to a power of two on upload.
 *
 * ## Why both assertions are needed
 *
 * These tests run under Linux Mesa software GL on CI (and self-skip on macOS,
 * where the bug actually lives, because [HeadlessGl] gates on `$DISPLAY`). Mesa
 * samples NPOT sub-regions *correctly* with or without the fix, so the rendered
 * pixels alone cannot detect a regression. The real guard is the assertion that
 * the backing texture's dimensions are powers of two — that fails on CI the
 * moment the POT-upload behaviour is removed. The pixel check is complementary:
 * it proves the padding did not shift the glyph/sprite slicing.
 */
class NpotTileSheetTest : FunSpec({

    test("an NPOT sheet is uploaded as a power-of-two texture and still slices correctly")
        .config(enabled = HeadlessGl.available) {
            val tile = 10
            val cols = 3
            val rows = 2
            // 3x2 tiles of 10x10 -> a 30x20 sheet; both axes are non-power-of-two.
            val colors =
                listOf(
                    listOf(Color.RED, Color.GREEN, Color.BLUE),
                    listOf(Color.YELLOW, Color.CYAN, Color.MAGENTA),
                )

            var widthInTiles = -1
            var heightInTiles = -1
            var texWidth = -1
            var texHeight = -1

            val pixels =
                HeadlessGl.render(cols * tile, rows * tile, Color.BLACK) {
                    // Author the NPOT sheet: one solid color per tile cell.
                    val sheetPixmap = Pixmap(cols * tile, rows * tile, Pixmap.Format.RGBA8888)
                    for (ry in 0 until rows) {
                        for (rx in 0 until cols) {
                            sheetPixmap.setColor(colors[ry][rx])
                            sheetPixmap.fillRectangle(rx * tile, ry * tile, tile, tile)
                        }
                    }
                    val file = File.createTempFile("kotile-npot", ".png").apply { deleteOnExit() }
                    PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
                    sheetPixmap.dispose()

                    val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), tile, tile)
                    widthInTiles = sheet.widthInTiles
                    heightInTiles = sheet.heightInTiles
                    // The region's backing texture is what actually got uploaded.
                    val backing = sheet.region(0, 0).texture
                    texWidth = backing.width
                    texHeight = backing.height

                    val canvas = KotileCanvas(tile, tile)
                    val renderer = SpriteTileRenderer(canvas, sheet)
                    for (ry in 0 until rows) {
                        for (rx in 0 until cols) {
                            renderer.drawTile(rx, ry, z = 0, staticTile = StaticTile(rx, ry))
                        }
                    }
                    renderer.render()
                    canvas.dispose()
                    sheet.dispose()
                }

            // Tile counts derive from the *source* image, unaffected by padding.
            widthInTiles shouldBe cols
            heightInTiles shouldBe rows

            // The fix itself: the texture is padded up to a power of two on each
            // axis, never smaller than the source. THIS is the regression guard.
            isPowerOfTwo(texWidth) shouldBe true
            isPowerOfTwo(texHeight) shouldBe true
            texWidth shouldBeGreaterThanOrEqualTo cols * tile
            texHeight shouldBeGreaterThanOrEqualTo rows * tile

            // Padding must not shift slicing: every cell renders its own color.
            for (ry in 0 until rows) {
                for (rx in 0 until cols) {
                    val avg =
                        pixels.averageColor(
                            rx * tile + 2,
                            ry * tile + 2,
                            rx * tile + tile - 2,
                            ry * tile + tile - 2,
                        )
                    val expected = colors[ry][rx]
                    avg.r.toDouble() shouldBe (expected.r.toDouble() plusOrMinus 0.1)
                    avg.g.toDouble() shouldBe (expected.g.toDouble() plusOrMinus 0.1)
                    avg.b.toDouble() shouldBe (expected.b.toDouble() plusOrMinus 0.1)
                }
            }
            pixels.dispose()
        }
})

private fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
