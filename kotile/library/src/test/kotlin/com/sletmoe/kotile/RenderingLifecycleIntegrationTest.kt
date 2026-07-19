package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Lifecycle/ordering edge cases for the classes a 1.0 consumer drives directly:
 * [AsciiTileWindow], [SpriteTileRenderer] and the [com.sletmoe.kotile.rendering.GridCompositeCache]
 * they wrap (krogue-yt7). Skipped unless a (software) GL context is available; see [HeadlessGl].
 */
class RenderingLifecycleIntegrationTest : FunSpec({

    // -------------------------------------------------------------------------
    // (a) double dispose
    // -------------------------------------------------------------------------

    test("AsciiTileWindow: a second dispose() call is a clean no-op").config(enabled = HeadlessGl.available) {
        HeadlessGl.render(40, 20, Color.BLACK) {
            val window =
                AsciiTileWindow.create {
                    widthInTiles = 4
                    heightInTiles = 2
                }
            window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
            window.render()
            window.dispose()
            window.dispose() // must not throw or double-free GPU resources
        }
    }

    test("SpriteTileRenderer: a second dispose() call is a clean no-op").config(enabled = HeadlessGl.available) {
        HeadlessGl.render(8, 8, Color.BLACK) {
            val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(Color.RED)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-lifecycle-dispose", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
            renderer.render()
            renderer.dispose()
            renderer.dispose() // must not throw or double-free GPU resources
            canvas.dispose()
            sheet.dispose()
        }
    }

    // -------------------------------------------------------------------------
    // (b) render before any cell is written
    // -------------------------------------------------------------------------

    test("AsciiTileWindow: rendering before any cell is written produces a valid empty frame, not a crash").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(40, 20, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 4
                        heightInTiles = 2
                    }
                window.render() // first paint, nothing ever written -- must not NPE on a null frameBuffer/batch
                window.dispose()
            }
        // Every cell is empty (topCellAt == null), so the composite is fully transparent and the
        // REPLACE blit leaves the underlying clear color showing through untouched.
        val avg = pixels.averageColor(0, 0, 40, 20)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // (c) resize during use: grow, shrink, and down to a 1x1 grid
    // -------------------------------------------------------------------------

    test("AsciiTileWindow: resizing mid-use reallocates the cache and redraws correctly at each new size").config(
        enabled = HeadlessGl.available,
    ) {
        // Outer buffer sized for the largest grid used below (6x6 cells @ 10px) so every assertion
        // stays within the live framebuffer regardless of which step wrote it.
        val pixels =
            HeadlessGl.render(60, 60, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 2
                        heightInTiles = 2
                    }
                window.resize(20, 20) // sync the canvas layout to the initial 2x2 grid

                window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
                window.render()

                // Grow 2x2 -> 4x4: old content must survive, and the newly available corner cell must
                // render at the CORRECT screen position -- this depends on the cache's per-frame pixel
                // height (used by the y-flip scissor math) being recomputed for the new grid size.
                window.resize(40, 40)
                window.drawTile(3, 3, StaticAsciiTile(' ', Color.WHITE, Color.GREEN))
                window.render()

                // Grow again 4x4 -> 6x6: same check, one more time.
                window.resize(60, 60)
                window.drawTile(5, 5, StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.render()

                // Shrink 6x6 -> 2x2: the now-out-of-bounds green/blue cells are dropped; a freshly
                // written cell within the smaller grid must still land at the right (smaller) position.
                window.resize(20, 20)
                window.drawTile(1, 1, StaticAsciiTile(' ', Color.WHITE, Color.PURPLE))
                window.render()

                // Shrink to the degenerate 1x1 grid: must not crash, and the one surviving cell (0,0,
                // still red from the very first write) must still composite correctly.
                window.resize(10, 10)
                window.render()

                window.dispose()
            }

        // After the final 1x1 render, cell (0,0) -- red since step one -- is all that's left.
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.r.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        cell00.g.toDouble() shouldBe (0.0 plusOrMinus 0.15)
        cell00.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)
        pixels.dispose()
    }

    test("AsciiTileWindow: growing the grid renders the newly revealed cell at the correct screen position").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(60, 60, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 3
                        heightInTiles = 3
                    }
                window.resize(30, 30)
                window.render() // first paint at 3x3, nothing written

                window.resize(60, 60) // grow 3x3 -> 6x6
                window.drawTile(5, 5, StaticAsciiTile(' ', Color.WHITE, Color.GREEN)) // new bottom-right corner
                window.render()
                window.dispose()
            }
        val corner = pixels.averageColor(50, 50, 60, 60)
        corner.g.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // (d) shared canvas/font outlive a window's dispose
    // -------------------------------------------------------------------------

    test("AsciiTileWindow: disposing one window leaves a shared canvas usable by a second window").config(
        enabled = HeadlessGl.available,
    ) {
        // RenderingIntegrationTest:493's note made concrete: createWithCanvas windows do NOT own the
        // canvas/font, so disposing window A must not tear down anything window B still needs.
        val pixels =
            HeadlessGl.render(40, 20, Color.BLACK) {
                val font = Fonts.cp437_10x10()
                val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)

                val windowA =
                    AsciiTileWindow.createWithCanvas(canvas, font) {
                        widthInTiles = 4
                        heightInTiles = 2
                    }
                val windowB =
                    AsciiTileWindow.createWithCanvas(canvas, font) {
                        widthInTiles = 4
                        heightInTiles = 2
                    }

                windowA.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
                windowA.render()
                windowA.dispose() // must NOT dispose the shared canvas/font

                windowB.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.GREEN))
                windowB.render() // exercises the still-shared canvas after A's dispose
                windowB.dispose()

                canvas.dispose()
                font.dispose()
            }
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.g.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        pixels.dispose()
    }
})
