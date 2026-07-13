package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.rendering.Effect
import com.sletmoe.kotile.rendering.EffectsLayer
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.Layer
import com.sletmoe.kotile.rendering.LayerStack
import com.sletmoe.kotile.rendering.PixelRect
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.rendering.UiLayer
import com.sletmoe.kotile.rendering.Widget
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

    test("drawSprite places a sprite at a sub-tile pixel offset (free layer)").config(enabled = HeadlessGl.available) {
        // The free-layer primitive (ADR-0018): draw a 4x4 sprite at content
        // pixel (2, 2) — a HALF-tile offset on a 4px grid, i.e. straddling four
        // cells rather than snapping to one. It must land at top-left-origin
        // pixels [2,6) x [2,6): proves both sub-tile placement and the y-flip.
        val pixels = HeadlessGl.render(12, 12, Color.BLACK) {
            val texPixmap = Pixmap(4, 4, Pixmap.Format.RGBA8888)
            texPixmap.setColor(Color.RED)
            texPixmap.fill()
            val texture = Texture(texPixmap)
            texPixmap.dispose()
            val region = TextureRegion(texture)

            val canvas = KotileCanvas(4, 4) // reflow: 3x3 grid of 4px cells at 1x
            canvas.begin()
            canvas.drawSprite(pxX = 2f, pxY = 2f, region = region, w = 4f, h = 4f)
            canvas.end()
            canvas.dispose()
            texture.dispose()
        }

        // The sprite occupies the offset rectangle...
        val onSprite = pixels.averageColor(2, 2, 6, 6)
        onSprite.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        onSprite.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        onSprite.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        // ...and NOT the cell-(0,0) corner it would fill if it were grid-snapped.
        val corner = pixels.averageColor(0, 0, 2, 2)
        corner.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
    }

    test("drawTile and drawSprite agree for a cell-aligned draw").config(enabled = HeadlessGl.available) {
        // drawTile is sugar over drawSprite: drawing cell (1, 1) via each path
        // must produce identical pixels. Guards the sugar's cell→pixel mapping
        // (including the y-flip) against the primitive.
        fun renderVia(useSprite: Boolean): Pixmap = HeadlessGl.render(12, 12, Color.BLACK) {
            val texPixmap = Pixmap(4, 4, Pixmap.Format.RGBA8888)
            texPixmap.setColor(Color.GREEN)
            texPixmap.fill()
            val texture = Texture(texPixmap)
            texPixmap.dispose()
            val region = TextureRegion(texture)

            val canvas = KotileCanvas(4, 4)
            canvas.begin()
            if (useSprite) {
                canvas.drawSprite(pxX = 4f, pxY = 4f, region = region, w = 4f, h = 4f)
            } else {
                canvas.drawTile(1, 1, region)
            }
            canvas.end()
            canvas.dispose()
            texture.dispose()
        }

        val viaTile = renderVia(useSprite = false)
        val viaSprite = renderVia(useSprite = true)
        // Cell (1,1) is content pixels [4,8) x [4,8) under the 4px reflow grid.
        viaTile.averageColor(4, 4, 8, 8).r.toDouble() shouldBe
            (viaSprite.averageColor(4, 4, 8, 8).r.toDouble() plusOrMinus 0.02)
        viaTile.averageColor(4, 4, 8, 8).g.toDouble() shouldBe
            (viaSprite.averageColor(4, 4, 8, 8).g.toDouble() plusOrMinus 0.02)
        viaTile.averageColor(4, 4, 8, 8).g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        viaTile.dispose()
        viaSprite.dispose()
    }

    test("LayerStack composites free layers back-to-front (painter's order)").config(enabled = HeadlessGl.available) {
        // Bottom layer fills the whole content blue; top layer paints a red
        // sprite over the top-left quadrant. The stack's draw order must let the
        // later layer win where they overlap, and the earlier show through
        // elsewhere.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val blue = solidTexture(Color.BLUE)
            val red = solidTexture(Color.RED)
            val canvas = KotileCanvas(8, 8) // reflow: one 8px cell; content is 8x8 px

            val stack = LayerStack(canvas)
            stack.add(Layer { c -> c.drawSprite(0f, 0f, TextureRegion(blue), w = 8f, h = 8f) })
            stack.add(Layer { c -> c.drawSprite(0f, 0f, TextureRegion(red), w = 4f, h = 4f) })
            stack.render()

            canvas.dispose()
            blue.dispose()
            red.dispose()
        }

        pixels.averageColor(0, 0, 4, 4).r.toDouble() shouldBe (1.0 plusOrMinus 0.1) // top-left: red wins
        pixels.averageColor(4, 4, 8, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1) // elsewhere: blue shows
        pixels.averageColor(4, 4, 8, 8).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("a grid renderer's asLayer composites under a free layer in one stack").config(enabled = HeadlessGl.available) {
        // AsciiTileWindow.asLayer draws the (blue-background) grid; a free red
        // sprite is stacked on top over the top-left cell. Proves a grid layer
        // and a free layer share one begin/end pass and overlay by draw order.
        val pixels = HeadlessGl.render(20, 20, Color.BLACK) {
            val font = Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx) // 10x10
            val window = AsciiTileWindow.createWithCanvas(canvas, font) {
                widthInTiles = 2
                heightInTiles = 2
                fitToWindow = false // fixed 2x2 grid -> 20x20 px at integer 1x
            }
            window.fill(AsciiTileDescriptor(' ', Color.WHITE, Color.BLUE)) // bg quads blue
            val red = solidTexture(Color.RED)

            val stack = LayerStack(canvas)
            stack.add(window.asLayer())
            stack.add(Layer { c -> c.drawSprite(0f, 0f, TextureRegion(red), w = 10f, h = 10f) })
            stack.render()

            window.dispose() // shared canvas + font are NOT disposed by the window
            red.dispose()
            canvas.dispose()
            font.dispose()
        }

        pixels.averageColor(0, 0, 10, 10).r.toDouble() shouldBe (1.0 plusOrMinus 0.1) // top-left cell: red sprite
        pixels.averageColor(10, 10, 20, 20).b.toDouble() shouldBe (1.0 plusOrMinus 0.1) // other cell: blue grid
        pixels.dispose()
    }

    test("EffectsLayer draws a spawned effect at its updated position").config(enabled = HeadlessGl.available) {
        // Spawn a 4x4 red effect at (0,0) moving right at 0.04 px/ms; a 100ms
        // update advances it +4px, so after update+render it must sit at
        // content pixels [4,8) x [0,4) — not its spawn cell. Drives the whole
        // stack.update -> stack.render pipeline through GL.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val red = solidTexture(Color.RED)
            val canvas = KotileCanvas(8, 8)
            val effects = EffectsLayer()
            val stack = LayerStack(canvas)
            stack.add(effects)

            effects.spawn(
                Effect(pxX = 0f, pxY = 0f, w = 4f, h = 4f, region = TextureRegion(red), velXPerMs = 0.04f),
            )
            stack.update(100)
            stack.render()

            effects.activeCount shouldBe 1 // no lifetime -> still active
            canvas.dispose()
            red.dispose()
        }

        pixels.averageColor(4, 0, 8, 4).r.toDouble() shouldBe (1.0 plusOrMinus 0.1) // moved here
        pixels.averageColor(0, 0, 4, 4).r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // vacated spawn spot
        pixels.dispose()
    }

    test("UiLayer draws a widget above the grid at its pixel bounds; hidden widgets don't draw").config(enabled = HeadlessGl.available) {
        // A blue-background 2x2 grid (20x20px) with a UiLayer on top. A visible
        // red widget occupies content pixels [2,8) x [2,8) — a free, sub-cell
        // rectangle straddling into cell (0,0). A second, hidden widget covers the
        // bottom-right cell; it must leave that cell showing the blue grid.
        val pixels = HeadlessGl.render(20, 20, Color.BLACK) {
            val font = Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx) // 10x10
            val window = AsciiTileWindow.createWithCanvas(canvas, font) {
                widthInTiles = 2
                heightInTiles = 2
                fitToWindow = false // fixed 2x2 grid -> 20x20 px at integer 1x
            }
            window.fill(AsciiTileDescriptor(' ', Color.WHITE, Color.BLUE))
            val red = solidTexture(Color.RED)

            val ui = UiLayer()
            ui.add(SolidWidget(PixelRect(2f, 2f, 6f, 6f), red))          // visible, on top
            ui.add(SolidWidget(PixelRect(10f, 10f, 10f, 10f), red, visible = false)) // hidden

            val stack = LayerStack(canvas)
            stack.add(window.asLayer()) // grid, below
            stack.add(ui)               // UI, on top
            stack.render()

            ui.widgetCount shouldBe 2
            window.dispose()
            red.dispose()
            canvas.dispose()
            font.dispose()
        }

        pixels.averageColor(2, 2, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)  // visible widget: red, over the grid
        pixels.averageColor(0, 0, 2, 2).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)  // outside its bounds: blue grid shows
        pixels.averageColor(12, 12, 20, 20).b.toDouble() shouldBe (1.0 plusOrMinus 0.1) // hidden widget: blue grid, not red
        pixels.averageColor(12, 12, 20, 20).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("sprite layers composite bottom-up: a transparent foreground reveals the background").config(enabled = HeadlessGl.available) {
        // Background terrain (blue, z=0) under a fully transparent foreground
        // sprite (z=1). Bottom-up compositing draws the background beneath the
        // foreground, so the blue shows through; the old top-cell-only path
        // would have drawn only the (invisible) foreground, leaving the black
        // clear color.
        val avg = renderLayered(background = Color.BLUE, foreground = Color(1f, 0f, 0f, 0f))
        avg.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }

    test("sprite layers composite bottom-up: an opaque foreground occludes the background").config(enabled = HeadlessGl.available) {
        // An opaque foreground (red, z=1) fully covers the background (blue,
        // z=0): compositing must not let occluded terrain bleed through.
        val avg = renderLayered(background = Color.BLUE, foreground = Color.RED)
        avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }

    test("sprite tiles smooth at a fractional scale but stay crisp at an integer scale").config(enabled = HeadlessGl.available) {
        // A sprite with a hard internal red|blue edge. At a fractional scale the
        // sharp-bilinear filter must blend across that edge (some pixel is
        // purple: both channels high); at an integer scale nearest-neighbour
        // keeps it crisp (every pixel is pure red or pure blue). This exercises
        // the shader on the SPRITE path (krogue-m2x "cover sprites").
        val fractional = maxEdgeBlend(FitScale, windowPx = 22) // 22/8 = 2.75x
        val integer = maxEdgeBlend(IntegerScale, windowPx = 22) // floor -> 2x

        fractional.toDouble() shouldBeGreaterThan 0.2 // a blended (purple) pixel exists
        integer.toDouble() shouldBe (0.0 plusOrMinus 0.06) // no blend: crisp edge
    }

    test("a fractional-scaled glyph grid still renders through the sharp-bilinear path").config(enabled = HeadlessGl.available) {
        // Smoke test for the glyph layer on the same shader path: a full-block
        // glyph on a FitScale fixed grid at a non-integer scale must still fill
        // its cell (the shader must not blank or corrupt the glyph).
        val pixels = HeadlessGl.render(20, 20, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 1
                heightInTiles = 1
                fitToWindow = false
                scalePolicy = FitScale
            }
            window.drawTile(0, 0, AsciiTileDescriptor('Û', Color.WHITE, Color.CLEAR))
            window.render()
            window.dispose()
        }
        // Center of the (scaled, centered) cell is solid white.
        val center = pixels.averageColor(8, 8, 12, 12)
        center.r.toDouble() shouldBeGreaterThan 0.8
        center.g.toDouble() shouldBeGreaterThan 0.8
        center.b.toDouble() shouldBeGreaterThan 0.8
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

/**
 * A 1x1 [color]-filled [Texture], stretched by [KotileCanvas.drawSprite] to any
 * size. Caller disposes. Must be created with a live GL context (inside
 * [HeadlessGl.render]).
 */
private fun solidTexture(color: Color): Texture {
    val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
    pixmap.setColor(color)
    pixmap.fill()
    return Texture(pixmap).also { pixmap.dispose() }
}

/** A [Widget] that fills its content-pixel [bounds] with [texture] — enough to prove free-UI rendering. */
private class SolidWidget(
    override val bounds: PixelRect,
    private val texture: Texture,
    override var visible: Boolean = true,
) : Widget {
    override fun render(canvas: KotileCanvas) {
        canvas.drawSprite(bounds.x, bounds.y, TextureRegion(texture), bounds.width, bounds.height)
    }
}

private val SHEET_BLUE = Color(0.3f, 0.3f, 0.9f, 1f)

/**
 * Renders a single 8x8 red/blue **checkerboard** sprite tile (a hard color edge
 * at every texel boundary) on a 1x1 fixed grid scaled to a [windowPx] window by
 * [policy], and returns the maximum "edge blend" found: `max over pixels of
 * min(r, b)`. A crisp (nearest) render yields ~0 (each pixel is pure red or pure
 * blue); a smoothed (sharp-bilinear) render yields a positive value where
 * adjacent texels blend to purple. The dense edges make the result robust to the
 * exact scale (a single centered edge can alias against the ~1px blend band).
 */
private fun maxEdgeBlend(policy: ScalePolicy, windowPx: Int): Float {
    val pixels = HeadlessGl.render(windowPx, windowPx, Color.BLACK) {
        val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        tilePixmap.blending = Pixmap.Blending.None
        for (ty in 0 until 8) {
            for (tx in 0 until 8) {
                tilePixmap.setColor(if ((tx + ty) % 2 == 0) Color.RED else Color.BLUE)
                tilePixmap.fillRectangle(tx, ty, 1, 1)
            }
        }
        val file = File.createTempFile("kotile-edge", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
        tilePixmap.dispose()

        val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
        val canvas = KotileCanvas(8, 8)
        canvas.useFixedGrid(1, 1, policy)
        val renderer = SpriteTileRenderer(canvas, sheet)
        renderer.drawTile(0, 0, z = 0, staticTile = StaticTile(0, 0))
        renderer.render()
        canvas.dispose()
        sheet.dispose()
    }

    var maxBlend = 0f
    for (y in 0 until windowPx) {
        for (x in 0 until windowPx) {
            val p = pixels.getPixel(x, y)
            val r = ((p ushr 24) and 0xff) / 255f
            val b = ((p ushr 8) and 0xff) / 255f
            maxBlend = maxOf(maxBlend, minOf(r, b))
        }
    }
    pixels.dispose()
    return maxBlend
}

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

/**
 * Renders a [background] tile on z=0 with a [foreground] tile on z=1 covering
 * the whole grid through a single [SpriteTileRenderer], and returns the average
 * resulting color. The two tiles are sliced from a synthetic 8x16 two-cell
 * sheet: cell (0,0) is [background], cell (0,1) is [foreground].
 */
private fun renderLayered(background: Color, foreground: Color): Color {
    val pixels = HeadlessGl.render(64, 64, Color.BLACK) {
        val sheetPixmap = Pixmap(8, 16, Pixmap.Format.RGBA8888)
        sheetPixmap.blending = Pixmap.Blending.None
        sheetPixmap.setColor(background)
        sheetPixmap.fillRectangle(0, 0, 8, 8) // tile (0,0): background terrain
        sheetPixmap.setColor(foreground)
        sheetPixmap.fillRectangle(0, 8, 8, 8) // tile (0,1): foreground entity
        val file = File.createTempFile("kotile-layered", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
        sheetPixmap.dispose()

        val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
        val canvas = KotileCanvas(8, 8)
        val renderer = SpriteTileRenderer(canvas, sheet)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                renderer.drawTile(x, y, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 0))
                renderer.drawTile(x, y, z = 1, staticTile = StaticTile(sheetX = 0, sheetY = 1))
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
