package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests for the sprite path's runtime cell sizing —
 * [com.sletmoe.kotile.rendering.TileRenderer.cellSizePx] (krogue-cj5), the peer of
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow.cellSizePx] (krogue-l23).
 *
 * The property is what a zoom control drives, so what has to hold is that the cells come out at
 * *exactly* the size asked for (asserted on rendered pixels, not just on the reported layout), that
 * the visible cell count follows the window rather than the other way round, and that clearing it puts
 * back whatever layout the canvas was configured with.
 *
 * Skipped unless a (software) GL context is available; see [HeadlessGl].
 */
class SpriteCellSizeIntegrationTest : FunSpec({

    /** Writes a [tileWidth] x [tileHeight] solid-[color] sheet to a temp PNG and slices it as one tile. */
    fun solidSheet(
        tileWidth: Int,
        tileHeight: Int,
        color: Color,
    ): TileSheet {
        val pixmap = Pixmap(tileWidth, tileHeight, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        pixmap.setColor(color)
        pixmap.fill()
        val file = File.createTempFile("kotile-cellsize", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
        pixmap.dispose()
        return TileSheet(Gdx.files.absolute(file.absolutePath), tileWidth, tileHeight)
    }

    test("cellSizePx draws tiles at exactly that size, with the cell count following the window")
        .config(enabled = HeadlessGl.available) {
            // 64x64 window, 8px art: reflow shows 8x8 cells. Asking for 16pt cells must halve that to
            // 4x4 and double the drawn square -- the count follows the size, not the other way round.
            var reflowedTiles = 0
            var chosenTiles = 0
            val pixels =
                HeadlessGl.render(64, 64, Color.BLACK) {
                    val sheet = solidSheet(8, 8, Color.RED)
                    val canvas = KotileCanvas(8, 8)
                    val renderer = SpriteTileRenderer(canvas, sheet)
                    reflowedTiles = renderer.widthInTiles

                    renderer.cellSizePx = 16
                    chosenTiles = renderer.widthInTiles
                    renderer.drawTile(0, 0, StaticSpriteTile(0, 0))
                    renderer.render()

                    renderer.dispose()
                    canvas.dispose()
                    sheet.dispose()
                }

            reflowedTiles shouldBe 8
            chosenTiles shouldBe 4

            // The one drawn cell covers exactly 16x16 px: red inside it, background just past it.
            pixels.averageColor(0, 0, 16, 16).r.toDouble() shouldBe (1.0 plusOrMinus 0.05)
            pixels.averageColor(16, 0, 32, 16).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
            pixels.averageColor(0, 16, 16, 32).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
            pixels.dispose()
        }

    test("clearing cellSizePx restores the fixed grid the canvas was configured with")
        .config(enabled = HeadlessGl.available) {
            // The canvas is driving a 4x4 fixed grid (IntegerScale doubles the 8px art to 16px on
            // screen in a 64x64 window). A chosen cell size has to take that over -- a scale policy
            // would otherwise override the very size being asked for -- and clearing it has to give the
            // fixed grid back rather than leaving the canvas reflowing.
            var chosenTiles = 0
            var restoredTiles = 0
            var restoredTilePx = 0
            val pixels =
                HeadlessGl.render(64, 64, Color.BLACK) {
                    val sheet = solidSheet(8, 8, Color.RED)
                    val canvas = KotileCanvas(8, 8)
                    canvas.useFixedGrid(4, 4, IntegerScale)
                    val renderer = SpriteTileRenderer(canvas, sheet)

                    renderer.cellSizePx = 32
                    chosenTiles = renderer.widthInTiles

                    renderer.cellSizePx = null
                    restoredTiles = renderer.widthInTiles
                    restoredTilePx = renderer.tileWidthPx
                    renderer.drawTile(0, 0, StaticSpriteTile(0, 0))
                    renderer.render()

                    renderer.dispose()
                    canvas.dispose()
                    sheet.dispose()
                }

            chosenTiles shouldBe 2 // 64pt window / 32pt cells, reflowed
            restoredTiles shouldBe 4 // back to the fixed grid's own count
            restoredTilePx shouldBe 8 // and to the art's native px, not the chosen cell

            // The fixed grid's IntegerScale is back too: the 8px tile is drawn 2x, filling 16x16 px.
            pixels.averageColor(0, 0, 16, 16).r.toDouble() shouldBe (1.0 plusOrMinus 0.05)
            pixels.averageColor(16, 0, 32, 16).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
            pixels.dispose()
        }

    test("cellSizePx keeps a non-square tile's aspect ratio")
        .config(enabled = HeadlessGl.available) {
            // Sprite art is often non-square (8x16 here), which the ascii path never has to deal with:
            // its chosen cell is square. Squaring the cell would squash the art, so the value is the
            // cell's *width* and the height follows the native ratio -- 16 wide asks for 16x32 cells.
            var cellWidthPx = 0
            var cellHeightPx = 0
            var columns = 0
            var rows = 0
            val pixels =
                HeadlessGl.render(64, 64, Color.BLACK) {
                    val sheet = solidSheet(8, 16, Color.RED)
                    val canvas = KotileCanvas(8, 16)
                    val renderer = SpriteTileRenderer(canvas, sheet)

                    renderer.cellSizePx = 16
                    cellWidthPx = renderer.tileWidthPx
                    cellHeightPx = renderer.tileHeightPx
                    columns = renderer.widthInTiles
                    rows = renderer.heightInTiles
                    renderer.drawTile(0, 0, StaticSpriteTile(0, 0))
                    renderer.render()

                    renderer.dispose()
                    canvas.dispose()
                    sheet.dispose()
                }

            cellWidthPx shouldBe 16
            cellHeightPx shouldBe 32
            columns shouldBe 4
            rows shouldBe 2

            // The drawn cell is 16 wide and 32 tall: red through y=31, background from y=32.
            pixels.averageColor(0, 0, 16, 32).r.toDouble() shouldBe (1.0 plusOrMinus 0.05)
            pixels.averageColor(0, 32, 16, 64).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
            pixels.averageColor(16, 0, 32, 32).r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
            pixels.dispose()
        }

    test("a resize keeps the chosen cell size and reflows the count around it")
        .config(enabled = HeadlessGl.available) {
            // The sizing a zoom control sets has to survive the application's resize callback: the
            // cells stay the size the user picked and the grid simply shows fewer of them.
            var tilePx = 0
            var columns = 0
            HeadlessGl.render(64, 64, Color.BLACK) {
                val sheet = solidSheet(8, 8, Color.RED)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)

                renderer.cellSizePx = 16
                renderer.resize(32, 32)
                tilePx = renderer.tileWidthPx
                columns = renderer.widthInTiles

                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }.dispose()

            tilePx shouldBe 16
            columns shouldBe 2 // 32pt window / 16pt cells
        }

    test("a renderer sharing a canvas refuses a chosen cell size")
        .config(enabled = HeadlessGl.available) {
            // Same rule the ascii path applies to a caller-owned canvas: retiling it and taking over
            // its layout mode would move every neighbouring pane, so a shared-canvas renderer may not.
            HeadlessGl.render(64, 64, Color.BLACK) {
                val sheet = solidSheet(8, 8, Color.RED)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet, sharesCanvas = true)

                shouldThrow<IllegalArgumentException> { renderer.cellSizePx = 16 }
                renderer.cellSizePx shouldBe null
                renderer.widthInTiles shouldBe 8 // untouched: the canvas still reflows at 8px tiles
                shouldNotThrowAny { renderer.cellSizePx = null } // clearing is always allowed

                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }.dispose()
        }
})
