package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.rendering.TileViewport
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.SpriteTile
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.LayeredTilemap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests verifying that [TileViewport] offsets work correctly for
 * both [AsciiTileWindow] and [SpriteTileRenderer]. Each test renders into a
 * small offscreen buffer and inspects the resulting pixels.
 *
 * Tests are skipped unless a (software) GL context is available; see [HeadlessGl].
 */
class ViewportIntegrationTest : FunSpec({

    // -----------------------------------------------------------------------
    // AsciiTileWindow viewport tests
    // -----------------------------------------------------------------------

    test("AsciiTileWindow: viewport offset maps logical cell to correct screen position")
        .config(enabled = HeadlessGl.available) {
        // 40×20 px framebuffer → 4×2 cells at 10×10 px each.
        // Logical world is 6×4. A red full-block is placed at logical (2, 1).
        // Setting the viewport origin to (2, 1) maps that logical cell to
        // screen cell (0, 0), so the top-left 10×10 pixels should be red.
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(6, 4)
            world.setCell(2, 1, 0, StaticAsciiTile('Û', Color.RED, Color.CLEAR))

            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(originX = 2, originY = 1))
            window.dispose()
        }

        // Screen cell (0, 0): should be red.
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.r.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        cell00.g.toDouble() shouldBe (0.0 plusOrMinus 0.15)
        cell00.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)

        // Screen cell (1, 0): should remain black (empty in world).
        val cell10 = pixels.averageColor(10, 0, 20, 10)
        cell10.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        cell10.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        cell10.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
    }

    test("AsciiTileWindow: zero viewport reproduces the same output as no-viewport render")
        .config(enabled = HeadlessGl.available) {
        // Placing a red tile at world (0, 0) and using TileViewport(0, 0)
        // should produce the same result as writing directly to the internal
        // grid and calling the no-arg render().
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(4, 2)
            world.setCell(0, 0, 0, StaticAsciiTile('Û', Color.RED, Color.CLEAR))

            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(0, 0))
            window.dispose()
        }

        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.r.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        pixels.dispose()
    }

    test("AsciiTileWindow: viewport past logical world edge leaves screen cells empty")
        .config(enabled = HeadlessGl.available) {
        // World is 4×2. Viewport origin (3, 1): only column 3 / row 1 of the
        // world is in view, and nothing is placed there. The tile placed at
        // (0, 0) is outside the visible window, so all screen cells stay black.
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(4, 2)
            world.setCell(0, 0, 0, StaticAsciiTile('Û', Color.RED, Color.CLEAR))

            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(originX = 3, originY = 1))
            window.dispose()
        }

        // The entire framebuffer should remain black.
        val avg = pixels.averageColor(0, 0, 40, 20)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        pixels.dispose()
    }

    test("AsciiTileWindow: tile becomes visible only after scrolling viewport to it")
        .config(enabled = HeadlessGl.available) {
        // World is 8×4. Blue background tile placed at logical (4, 0).
        // With viewport (0, 0) it is at screen column 4, outside the 4-wide
        // window — screen stays black. With viewport (4, 0) it maps to screen
        // cell (0, 0) and should appear blue.
        val world = LayeredTilemap<AsciiTile>(8, 4)
        world.setCell(4, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.BLUE))

        // Render without scrolling — tile is off-screen.
        val pixelsNoScroll = HeadlessGl.render(40, 20, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(0, 0))
            window.dispose()
        }
        val avgNoScroll = pixelsNoScroll.averageColor(0, 0, 10, 10)
        avgNoScroll.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixelsNoScroll.dispose()

        // Render scrolled to (4, 0) — tile appears at screen cell (0, 0).
        val pixelsScrolled = HeadlessGl.render(40, 20, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(4, 0))
            window.dispose()
        }
        val avgScrolled = pixelsScrolled.averageColor(0, 0, 10, 10)
        avgScrolled.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixelsScrolled.dispose()
    }

    // -----------------------------------------------------------------------
    // SpriteTileRenderer (TileRenderer) viewport tests
    // -----------------------------------------------------------------------

    test("SpriteTileRenderer: viewport offset places logical tile at correct screen cell")
        .config(enabled = HeadlessGl.available) {
        // 40×20 px at 10×10 tiles → 4×2 screen cells.
        // Logical world is 6×4. A red tile is placed at logical (2, 1) and
        // the viewport is set to (2, 1), expecting red at screen cell (0, 0).
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val tilePixmap = Pixmap(10, 10, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(Color.RED)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-viewport-sprite", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 10, 10)
            val canvas = KotileCanvas(10, 10)
            val renderer = SpriteTileRenderer(canvas, sheet)

            val world = LayeredTilemap<SpriteTile>(6, 4)
            world.setCell(2, 1, 0, StaticSpriteTile(0, 0))

            renderer.render(world, TileViewport(originX = 2, originY = 1))
            canvas.dispose()
            sheet.dispose()
        }

        // Screen cell (0, 0) — top-left 10×10 pixels — should be red.
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.r.toDouble() shouldBe (1.0 plusOrMinus 0.15)
        cell00.g.toDouble() shouldBe (0.0 plusOrMinus 0.15)
        cell00.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)

        // Screen cell (1, 0) — should stay at the clear color.
        val cell10 = pixels.averageColor(10, 0, 20, 10)
        cell10.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("SpriteTileRenderer: viewport past world edge leaves all screen cells empty")
        .config(enabled = HeadlessGl.available) {
        // World is 4×2. Viewport is (4, 0): every logical X in [4, 7] is
        // outside the world bounds, so nothing should render.
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val tilePixmap = Pixmap(10, 10, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(Color.RED)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-viewport-sprite-oob", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 10, 10)
            val canvas = KotileCanvas(10, 10)
            val renderer = SpriteTileRenderer(canvas, sheet)

            val world = LayeredTilemap<SpriteTile>(4, 2)
            world.setCell(0, 0, 0, StaticSpriteTile(0, 0))

            renderer.render(world, TileViewport(originX = 4, originY = 0))
            canvas.dispose()
            sheet.dispose()
        }

        val avg = pixels.averageColor(0, 0, 40, 20)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.05)
        pixels.dispose()
    }

    // -----------------------------------------------------------------------
    // krogue-c0q: per-observer dirty tracking for render(source, viewport)
    // -----------------------------------------------------------------------

    test("AsciiTileWindow: a write to a shared world is reflected on the next render(source, viewport) call")
        .config(enabled = HeadlessGl.available) {
        // Regression guard for the render(source, viewport) cache added in krogue-c0q: it must
        // still pick up a write made directly to the caller-owned world between two render calls.
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(4, 2)
            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(0, 0)) // first paint: nothing placed yet
            world.setCell(0, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
            window.render(world, TileViewport(0, 0)) // must pick up the write with no viewport change
            window.dispose()
        }
        pixels.averageColor(0, 0, 10, 10).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("AsciiTileWindow: two windows sharing one world each see their own update regardless of render order")
        .config(enabled = HeadlessGl.available) {
        // The exact bug krogue-c0q closes: a single consumable "dirty since last render" flag on
        // the shared world would be stolen by whichever window renders first, leaving the other
        // wrongly believing nothing changed. Window B renders SECOND, after A, and must still show
        // its own update.
        val world = LayeredTilemap<AsciiTile>(8, 2)
        val pixelsB = HeadlessGl.render(40, 20, Color.BLACK) {
            val windowA = AsciiTileWindow.create { widthInTiles = 4; heightInTiles = 2; fitToWindow = false }
            val windowB = AsciiTileWindow.create { widthInTiles = 4; heightInTiles = 2; fitToWindow = false }
            windowA.render(world, TileViewport(0, 0)) // first paint, world columns [0, 4)
            windowB.render(world, TileViewport(4, 0)) // first paint, world columns [4, 8)

            // Changes visible only to A (world col 1) and only to B (world col 5).
            world.setCell(1, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
            world.setCell(5, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.GREEN))

            windowA.render(world, TileViewport(0, 0)) // A renders first...
            windowB.render(world, TileViewport(4, 0)) // ...then B: must still see its own change
            windowA.dispose()
            windowB.dispose()
        }
        // World col 5 -> B's local col 1 -> screen pixels [10, 20).
        pixelsB.averageColor(10, 0, 20, 10).g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixelsB.dispose()
    }

    // -----------------------------------------------------------------------
    // krogue-flj: cross-frame scroll invalidation and shared-source animation,
    // both on a persistent (reused) window/cache rather than a fresh one per frame
    // -----------------------------------------------------------------------

    test("AsciiTileWindow: scrolling the viewport on a persistent renderer reveals new content and drops the old")
        .config(enabled = HeadlessGl.available) {
        // World 8x2: red at logical (0,0), green at logical (4,0). ONE window renders at origin
        // (0,0) first (red visible at screen (0,0)), then scrolls to origin (4,0) (green now at
        // screen (0,0)). Reusing the same window/cache across both renders is what exercises
        // ViewportDirtyTracker's viewportChanged -> markAllDirty branch and the resize/-1 sentinel
        // in lastVersions -- the existing scroll test in this file uses two separate HeadlessGl
        // windows, which only ever hits each renderer's own first-paint path.
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(8, 2)
            world.setCell(0, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
            world.setCell(4, 0, 0, StaticAsciiTile(' ', Color.WHITE, Color.GREEN))

            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(0, 0)) // first paint: red at screen (0,0)
            window.render(world, TileViewport(4, 0)) // scrolled: green now at screen (0,0)
            window.dispose()
        }

        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        cell00.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // red must not linger from the cache
        pixels.dispose()
    }

    test(
        "AsciiTileWindow: an animated tile on a shared source keeps animating across render(source, viewport) " +
            "calls with no writes between them",
    ).config(enabled = HeadlessGl.available) {
        // The exact krogue-asz symptom (animation freezing on its first-painted frame), but on the
        // shared-source render(source, viewport) path instead of the internal-tilemap path krogue-drk
        // already guards: ViewportDirtyTracker.isAnimatedAt must force the cell dirty on every call
        // even though nothing is ever written to `world` between the two render() calls below.
        val tile = AnimatedAsciiTile(
            frames = listOf(
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.RED), durationMs = 100),
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.GREEN), durationMs = 100),
            ),
        )
        val pixels = HeadlessGl.render(40, 20, Color.BLACK) {
            val world = LayeredTilemap<AsciiTile>(4, 2)
            world.setCell(0, 0, 0, tile)

            val window = AsciiTileWindow.create {
                widthInTiles = 4
                heightInTiles = 2
                fitToWindow = false
            }
            window.render(world, TileViewport(0, 0), elapsedMs = 0) // frame 0: red
            window.render(world, TileViewport(0, 0), elapsedMs = 150) // frame 1: green -- no writes between
            window.dispose()
        }
        val cell00 = pixels.averageColor(0, 0, 10, 10)
        cell00.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        cell00.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }
})
