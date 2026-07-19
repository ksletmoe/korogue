package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests for the clear/clearLayer/fill/query surfaces and dirty-tracking\n * recomposite behavior (krogue-1qb, krogue-0y8, krogue-ls4, krogue-r0f): mutations\n * that must not leave stale composite-cache content or bleed into untouched cells.\n * Skipped unless a (software) GL context is available; see [HeadlessGl].
 */
class RenderingClearAndDirtyIntegrationTest : FunSpec({
    // -------------------------------------------------------------------------
    // krogue-1qb: pixel coverage for AsciiTileWindow.clear/clearLayer -- the mirrors of the
    // sprite cases added in krogue-0y8. Both carry the same "collect the vacated cells, then
    // mark them dirty" bookkeeping, and it was only pixel-tested on the sprite side.
    // -------------------------------------------------------------------------

    test("krogue-1qb: AsciiTileWindow.clear drops every layer, leaving no stale cache content").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(10, 10, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 1
                        heightInTiles = 1
                    }
                window.drawTile(0, 0, z = 0, tile = StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.drawTile(0, 0, z = 1, tile = StaticAsciiTile(' ', Color.WHITE, Color.RED))
                window.render() // first paint: both layers, red on top
                window.clear()
                window.render() // recomposite must drop both, not retain them from the cache
                window.dispose()
            }
        val avg = pixels.averageColor(0, 0, 10, 10)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-1qb: AsciiTileWindow.clearLayer clears only its own layer and reveals the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // The sharpest test of clearLayer's bookkeeping: it must dirty the cells it vacated (or
        // the red stays, stale) without disturbing z=0 (or the blue vanishes too).
        val pixels =
            HeadlessGl.render(10, 10, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 1
                        heightInTiles = 1
                    }
                window.drawTile(0, 0, z = 0, tile = StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.drawTile(0, 0, z = 1, tile = StaticAsciiTile(' ', Color.WHITE, Color.RED))
                window.render() // first paint: red hides blue (top-cell-wins)
                window.clearLayer(1)
                window.render()
                window.dispose()
            }
        val avg = pixels.averageColor(0, 0, 10, 10)
        avg.b.toDouble() shouldBe (1.0 plusOrMinus 0.1) // z=0 survived
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // z=1 is gone, not stale
        pixels.dispose()
    }

    test("krogue-1qb: AsciiTileWindow.clearLayer on a never-written layer is a no-op").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(10, 10, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 1
                        heightInTiles = 1
                    }
                window.drawTile(0, 0, z = 0, tile = StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.clearLayer(99) // must not throw, and must not disturb z=0
                window.render()
                window.dispose()
            }
        pixels.averageColor(0, 0, 10, 10).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test(
        "krogue-drk: AsciiTileWindow renders identical output across repeated render calls with no writes between them",
    ).config(
        enabled = HeadlessGl.available,
    ) {
        // The cache-hit path (dirty == false) must still reproduce the same frame, not blank/stale.
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.render()
                window.render() // cache hit: no writes since the previous render
                window.render()
                window.dispose()
            }
        pixels.averageColor(0, 0, 10, 10).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-drk: an AnimatedAsciiTile keeps animating across renders with no writes between them").config(
        enabled = HeadlessGl.available,
    ) {
        // Placing an animated tile is sticky (ADR-0024): every render call must recomposite even
        // though nothing was written between the two render() calls below, or the animation would
        // visibly freeze on its first-painted frame.
        val tile =
            AnimatedAsciiTile(
                frames =
                    listOf(
                        AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.RED), durationMs = 100),
                        AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.GREEN), durationMs = 100),
                    ),
            )
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.drawTile(0, 0, tile)
                window.render(elapsedMs = 0) // first paint: frame 0 (red)
                window.render(elapsedMs = 150) // frame 1 (green) -- no writes between these two calls
                window.dispose()
            }
        val topLeft = pixels.averageColor(0, 0, 10, 10)
        topLeft.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        topLeft.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-xcx: a consumer-supplied DynamicAsciiTile keeps repainting, not just AnimatedAsciiTile").config(
        enabled = HeadlessGl.available,
    ) {
        // The window's per-frame repaint tracking must key on the DynamicAsciiTile *branch*, not on
        // the built-in AnimatedAsciiTile class -- otherwise a consumer's own time-driven tile paints
        // once and then freezes, since nothing is written between the two render() calls below.
        val custom =
            object : DynamicAsciiTile {
                override fun resolveAt(elapsedMs: Long): StaticAsciiTile =
                    StaticAsciiTile(' ', Color.WHITE, if (elapsedMs < 100) Color.RED else Color.GREEN)
            }
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.drawTile(0, 0, custom)
                window.render(elapsedMs = 0) // first paint: red
                window.render(elapsedMs = 150) // green -- no writes between these two calls
                window.dispose()
            }
        val topLeft = pixels.averageColor(0, 0, 10, 10)
        topLeft.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        topLeft.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-drk: SpriteTileRenderer's composite cache reflects a clearTile, not stale content").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
                val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
                tilePixmap.setColor(Color.RED)
                tilePixmap.fill()
                val file = File.createTempFile("kotile-drk-clear", ".png").apply { deleteOnExit() }
                PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
                tilePixmap.dispose()

                val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
                renderer.render() // first paint: red
                renderer.clearTile(0, 0, z = 0)
                renderer.render() // recomposite must drop the red, not retain it from the cache
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        pixels.averageColor(0, 0, 8, 8).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // krogue-0y8 (ADR-0028): the clear/fill/query surface TileRenderer gained when it
    // was aligned to AsciiTileWindow. Each of these mutates the composite cache's dirty
    // state, so they are exactly the krogue-drk stale-content shape -- assert on pixels
    // after a second render, not just on the tilemap.
    // -------------------------------------------------------------------------

    /**
     * Writes a 16x8 two-tile sheet (left tile red, right tile blue) and returns its path.
     * Must be called inside a [HeadlessGl.render] block -- it needs `Gdx.files`.
     */
    fun twoTileSheetPath(name: String): String {
        val pixmap = Pixmap(16, 8, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.RED)
        pixmap.fillRectangle(0, 0, 8, 8)
        pixmap.setColor(Color.BLUE)
        pixmap.fillRectangle(8, 0, 8, 8)
        val file = File.createTempFile(name, ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
        pixmap.dispose()
        return file.absolutePath
    }

    val redTile = StaticSpriteTile(0, 0)
    val blueTile = StaticSpriteTile(1, 0)

    test("krogue-0y8: TileRenderer.fill covers every cell on z=0").config(enabled = HeadlessGl.available) {
        // 16x16 window of 8x8 tiles = a 2x2 grid; fill must reach all four cells, not just (0,0).
        val pixels =
            HeadlessGl.render(16, 16, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-fill")), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.fill(redTile)
                renderer.render()
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        // Every corner cell is red.
        for ((x, y) in listOf(0 to 0, 8 to 0, 0 to 8, 8 to 8)) {
            val avg = pixels.averageColor(x, y, x + 8, y + 8)
            avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
            avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        }
        pixels.dispose()
    }

    test("krogue-0y8: TileRenderer.clear drops every layer, leaving no stale cache content").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-clear")), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.drawTile(0, 0, z = 0, tile = redTile)
                renderer.drawTile(0, 0, z = 1, tile = blueTile)
                renderer.render() // first paint: both layers
                renderer.clear()
                renderer.render() // recomposite must drop both, not retain them from the cache
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        val avg = pixels.averageColor(0, 0, 8, 8)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-0y8: TileRenderer.clearLayer clears only its own layer and reveals the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // The sharpest test of clearLayer's bookkeeping: it must dirty the cells it vacated
        // (or the blue stays, stale) without disturbing z=0 (or the red vanishes too).
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-clearlayer")), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.drawTile(0, 0, z = 0, tile = redTile)
                renderer.drawTile(0, 0, z = 1, tile = blueTile) // opaque, so it hides the red
                renderer.render() // first paint: blue over red
                renderer.clearLayer(1)
                renderer.render()
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        val avg = pixels.averageColor(0, 0, 8, 8)
        avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1) // z=0 survived
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // z=1 is gone, not stale
        pixels.dispose()
    }

    test("krogue-0y8: TileRenderer.clearLayer on a never-written layer is a no-op").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-clearlayer-noop")), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.drawTile(0, 0, z = 0, tile = redTile)
                renderer.clearLayer(99) // must not throw, and must not disturb z=0
                renderer.render()
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        pixels.averageColor(0, 0, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-0y8: TileRenderer's z-defaulted drawTile and clearTile both address layer 0").config(
        enabled = HeadlessGl.available,
    ) {
        // Proves the defaults really are z=0 on both sides: a z-defaulted write is removed by
        // clearLayer(0), and a z-defaulted clear removes an explicit z=0 write.
        val pixels =
            HeadlessGl.render(16, 8, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-zdefault")), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                renderer.drawTile(0, 0, redTile) // z defaults to 0
                renderer.drawTile(1, 0, z = 0, tile = redTile)
                renderer.render()
                renderer.clearLayer(0) // removes the z-defaulted write at (0,0)
                renderer.drawTile(1, 0, z = 0, tile = redTile)
                renderer.clearTile(1, 0) // z defaults to 0, so this removes it again
                renderer.render()
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        pixels.averageColor(0, 0, 8, 8).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.averageColor(8, 0, 16, 8).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("krogue-0y8: TileRenderer.topTileAt returns the highest-z tile, or null when empty").config(
        enabled = HeadlessGl.available,
    ) {
        // A pure query, but TileRenderer needs a KotileCanvas, which needs a GL context.
        var empty: Any? = "unset"
        var top: Any? = null
        var topByPosition: Any? = null
        var afterClear: Any? = "unset"
        HeadlessGl.render(8, 8, Color.BLACK) {
            val sheet = TileSheet(Gdx.files.absolute(twoTileSheetPath("kotile-0y8-query")), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            empty = renderer.topTileAt(0, 0)
            renderer.drawTile(0, 0, z = 0, tile = redTile)
            renderer.drawTile(0, 0, z = 1, tile = blueTile)
            top = renderer.topTileAt(0, 0)
            topByPosition = renderer.topTileAt(Vector2Int(0, 0))
            renderer.clearLayer(1)
            afterClear = renderer.topTileAt(0, 0)
            renderer.dispose()
            canvas.dispose()
            sheet.dispose()
        }.dispose()
        empty shouldBe null
        top shouldBe blueTile // highest z wins, not the z=0 red
        topByPosition shouldBe blueTile // the Vector2Int overload agrees
        afterClear shouldBe redTile // z=0 shows through once z=1 is cleared
    }

    test(
        "krogue-drk: TileRenderer keeps an AnimatedSpriteTile animating across renders with no writes between them",
    ).config(
        enabled = HeadlessGl.available,
    ) {
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
                val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
                tilePixmap.setColor(SHEET_BLUE)
                tilePixmap.fill()
                val file = File.createTempFile("kotile-drk-anim", ".png").apply { deleteOnExit() }
                PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
                tilePixmap.dispose()

                val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
                val canvas = KotileCanvas(8, 8)
                val renderer = SpriteTileRenderer(canvas, sheet)
                val region = sheet.region(0, 0)
                val shimmering =
                    AnimatedSpriteTile(
                        frames =
                            listOf(
                                AnimationFrame(region, durationMs = 100),
                                AnimationFrame(region, durationMs = 100, tint = Color.GREEN),
                            ),
                    )
                renderer.drawTile(0, 0, z = 0, tile = shimmering)
                renderer.render(elapsedMs = 0) // first paint: frame 0 (sheet's own blue)
                renderer.render(elapsedMs = 150) // frame 1 (green) -- no writes between these two calls
                renderer.dispose()
                canvas.dispose()
                sheet.dispose()
            }
        // GREEN(0,1,0) multiplies onto the sheet's own blue (0.3, 0.3, 0.9): r and b drop to 0, g is
        // capped at the sheet's own 0.3 -- not 1.0, since tint only ever darkens, never brightens
        // past the source texture. g alone can't distinguish this from a stale frame-0 WHITE-tint
        // render (both average 0.3 there), so assert on r, which frame 0 leaves at 0.3 and only
        // frame 1's override zeroes.
        val avg = pixels.averageColor(0, 0, 8, 8)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // krogue-ls4: partial-dirty recomposite must not bleed into untouched cells
    // -------------------------------------------------------------------------

    test(
        "krogue-ls4: partial-dirty recomposite changes only the written cell, leaving an " +
            "untouched neighbor's pixels intact",
    ).config(enabled = HeadlessGl.available) {
        // The whole reason the partial-dirty path exists: changing ONE cell on a multi-cell grid
        // must not bleed into (or wipe) an adjacent, unchanged cell's per-cell glScissor+glClear.
        // Reuses ONE persistent window across two renders -- a fresh window per frame would only
        // ever hit the fully-dirty first-paint branch, never this one.
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.drawTile(1, 0, StaticAsciiTile(' ', Color.WHITE, Color.GREEN))
                window.render() // first paint: both cells set, cache fully dirty
                window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED)) // only (0,0) changes
                window.render() // partial-dirty recomposite: only (0,0)'s scissor rect should be touched
                window.dispose()
            }
        // Changed cell now red...
        val changed = pixels.averageColor(0, 0, 10, 10)
        changed.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        changed.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        // ...adjacent unchanged cell still shows its own prior (green) content, not bled or cleared.
        val untouched = pixels.averageColor(10, 0, 20, 10)
        untouched.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        untouched.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // krogue-r0f: removing the top z-layer at a cell must reveal the layer beneath, through the cache
    // -------------------------------------------------------------------------

    test(
        "krogue-r0f: removing the top layer at a cell recomposites to reveal the layer beneath, through the cache",
    ).config(enabled = HeadlessGl.available) {
        // Two stacked layers at the same cell: an opaque top (red) over a differently-colored bottom
        // (blue). On a persistent renderer, removing the top layer and re-rendering must recomposite
        // to the bottom layer's color -- not retain the removed top layer's stale cached pixels, which
        // is exactly what would happen if the cell were redrawn without first being cleared.
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 8
                        heightInTiles = 4
                    }
                window.drawTile(0, 0, z = 0, tile = StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                window.drawTile(0, 0, z = 1, tile = StaticAsciiTile(' ', Color.WHITE, Color.RED))
                window.render() // first paint: top (red) layer wins
                window.clearTile(0, 0, z = 1) // remove the top layer
                window.render() // must recomposite to reveal the bottom (blue) layer
                window.dispose()
            }
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        cell00.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }
})
