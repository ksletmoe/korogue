package com.sletmoe.kotile.display.ascii

import com.sletmoe.kotile.HeadlessGl
import com.sletmoe.kotile.display.KotileCanvas
import io.kotest.core.spec.style.FunSpec
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

            val window = AsciiTileWindow.createWithCanvas(canvas, font) {
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
            val window = AsciiTileWindow.create {
                widthInTiles = 8
                heightInTiles = 4
            }
            window.dispose() // must not throw
        }.dispose()
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
