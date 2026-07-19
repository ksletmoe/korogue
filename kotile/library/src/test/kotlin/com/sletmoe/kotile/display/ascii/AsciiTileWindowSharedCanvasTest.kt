package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.HeadlessGl
import com.sletmoe.kotile.averageColor
import com.sletmoe.kotile.display.KotileCanvas
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Tests for the [AsciiTileWindow.createWithCanvas] shared-canvas factory,
 * verifying the dispose-ownership contract: a window that received an external
 * [KotileCanvas] must not dispose it; only the owner should.
 *
 * Tests that need a real GL context are guarded by [HeadlessGl.available].
 */
class AsciiTileWindowSharedCanvasTest : FunSpec({

    test("createWithCanvas: disposing the window does not dispose the external canvas")
        .config(enabled = HeadlessGl.available) {
            HeadlessGl.render(80, 40, com.badlogic.gdx.graphics.Color.BLACK) {
                val font = Fonts.cp437_10x10()
                val canvas = TrackingCanvas(font.charWidthPx, font.charHeightPx)

                val window =
                    AsciiTileWindow.createWithCanvas(canvas, font) {
                        widthInTiles = 8
                        heightInTiles = 4
                        fitToWindow = false
                    }

                // Disposing the window must NOT dispose the shared canvas.
                window.dispose()
                canvas.disposeCount shouldBe 0

                // Disposing the font externally (owned by the caller here).
                font.dispose()
                // Owner now disposes the canvas.
                canvas.dispose()
                canvas.disposeCount shouldBe 1
            }.dispose()
        }

    test("create (DSL): disposing the window disposes its own canvas")
        .config(enabled = HeadlessGl.available) {
            // Regression guard: the existing create {} path must still dispose its canvas.
            // We verify indirectly: the window is created and disposed without error.
            HeadlessGl.render(80, 40, com.badlogic.gdx.graphics.Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.dispose() // must not throw
            }.dispose()
        }

    // krogue-a24: a full-width window that populates only its right half shares a canvas with a
    // neighbor occupying the left half. The default authoritative REPLACE blit erases the neighbor's
    // pixels in the columns this window leaves empty; sharesCanvas = true blits NORMAL and preserves
    // them. The two halves are 4 cells (40px) wide each in an 8x4 grid (80x40px), no fitToWindow so
    // the fixed grid maps 1:1 to the render target.
    test("sharesCanvas = true: a window's empty cells preserve a neighbor pane; the default erases it")
        .config(enabled = HeadlessGl.available) {
            fun leftHalfAfterRightPaneRenders(sharesCanvas: Boolean): Color {
                val pixels =
                    HeadlessGl.render(80, 40, Color.BLACK) {
                        val font = Fonts.cp437_10x10()
                        val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)
                        // Neighbor pane: paints the whole canvas green — its left half is what must survive.
                        val neighbor =
                            AsciiTileWindow.createWithCanvas(canvas, font) {
                                widthInTiles = 8
                                heightInTiles = 4
                                fitToWindow = false
                            }
                        // Target pane: full width, but only the right half (cols 4..7) is ever populated.
                        val target =
                            AsciiTileWindow.createWithCanvas(canvas, font) {
                                widthInTiles = 8
                                heightInTiles = 4
                                fitToWindow = false
                                this.sharesCanvas = sharesCanvas
                            }
                        for (x in 0 until 8) {
                            for (y in 0 until 4) neighbor.drawTile(x, y, StaticAsciiTile(' ', Color.CLEAR, Color.GREEN))
                        }
                        for (x in 4 until 8) {
                            for (y in 0 until 4) target.drawTile(x, y, StaticAsciiTile(' ', Color.CLEAR, Color.RED))
                        }
                        neighbor.render()
                        target.render() // renders last; its blit decides the left half's fate
                        neighbor.dispose()
                        target.dispose()
                        canvas.dispose()
                        font.dispose()
                    }
                // Left half is cols 0..3 -> px 0..39; sample its interior, away from the seam and edges.
                val left = pixels.averageColor(5, 5, 34, 34)
                pixels.dispose()
                return left
            }

            // NORMAL blit (the fix): the neighbor's green left half survives.
            val preserved = leftHalfAfterRightPaneRenders(sharesCanvas = true)
            preserved.g shouldBeGreaterThan 0.5f
            preserved.r shouldBeLessThan 0.5f

            // Default REPLACE blit (the a24 bug): the target's empty left cells erase the neighbor.
            val erased = leftHalfAfterRightPaneRenders(sharesCanvas = false)
            erased.g shouldBeLessThan 0.5f
        }
})

/**
 * A [KotileCanvas] subclass that counts how many times [dispose] has been
 * called, used to verify ownership semantics without external mocking libraries.
 */
private class TrackingCanvas(tileWidthPx: Int, tileHeightPx: Int) : KotileCanvas(tileWidthPx, tileHeightPx) {
    /** Number of times [dispose] has been called on this instance. */
    var disposeCount: Int = 0
        private set

    override fun dispose() {
        disposeCount++
        super.dispose()
    }
}
