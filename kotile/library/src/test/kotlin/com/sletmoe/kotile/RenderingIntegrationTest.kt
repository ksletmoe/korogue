package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.BufferUtils
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.Effect
import com.sletmoe.kotile.rendering.EffectsLayer
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.Layer
import com.sletmoe.kotile.rendering.LayerStack
import com.sletmoe.kotile.rendering.PixelRect
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.rendering.TileRenderer
import com.sletmoe.kotile.rendering.UiLayer
import com.sletmoe.kotile.rendering.Widget
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
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
            window.drawTile(0, 0, StaticAsciiTile('Û', Color.RED, Color.CLEAR))
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

    test("TileRenderer applies an AnimatedSpriteTile's per-frame tint, not just its constant tint").config(
        enabled = HeadlessGl.available,
    ) {
        // krogue-2ur: frame 0 has no override (renders at the tile's WHITE constant tint, i.e.
        // the sheet's own blue); frame 1 overrides to green. Proves TileRenderer.renderGrid calls
        // entry.tintFor(elapsedMs) — not the old constant entry.tint — for the DynamicSpriteTile branch.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(SHEET_BLUE)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-shimmer", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            val region = sheet.region(0, 0)
            val shimmering = AnimatedSpriteTile(
                frames = listOf(
                    AnimationFrame(region, durationMs = 100),
                    AnimationFrame(region, durationMs = 100, tint = Color.GREEN),
                ),
            )
            renderer.drawTile(0, 0, z = 0, tile = shimmering)
            renderer.render(elapsedMs = 0)
            renderer.dispose()
            canvas.dispose()
            sheet.dispose()
        }

        // Frame 0 (elapsedMs=0): no override -> the sheet's own blue (0.3, 0.3, 0.9) shows through
        // at the implicit WHITE constant tint, unscaled.
        val frame0 = pixels.averageColor(0, 0, 8, 8)
        frame0.b.toDouble() shouldBe (0.9 plusOrMinus 0.1)
        frame0.r.toDouble() shouldBe (0.3 plusOrMinus 0.1)

        val pixelsFrame1 = HeadlessGl.render(8, 8, Color.BLACK) {
            val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(SHEET_BLUE)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-shimmer2", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            val region = sheet.region(0, 0)
            val shimmering = AnimatedSpriteTile(
                frames = listOf(
                    AnimationFrame(region, durationMs = 100),
                    AnimationFrame(region, durationMs = 100, tint = Color.GREEN),
                ),
            )
            renderer.drawTile(0, 0, z = 0, tile = shimmering)
            renderer.render(elapsedMs = 150) // into frame 1: green override
            renderer.dispose()
            canvas.dispose()
            sheet.dispose()
        }

        // Frame 1 (elapsedMs=150): GREEN(0,1,0) override multiplies onto the sheet's own blue, so r
        // and b (both zeroed by GREEN's own r/b components) drop out while g is capped at the
        // sheet's own 0.3 -- tint is multiplicative, not a replacement, so it can only ever darken a
        // channel, never brighten one past the source texture's own value.
        val frame1 = pixelsFrame1.averageColor(0, 0, 8, 8)
        frame1.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        frame1.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
        pixelsFrame1.dispose()
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

    test("drawSprite rotates 90 degrees clockwise about the sprite's center (krogue-m05)").config(
        enabled = HeadlessGl.available,
    ) {
        // A left(RED)/right(BLUE) split sprite, unrotated, has RED on the left. A positive
        // rotationDeg turns it clockwise on screen (per drawSprite's doc): rotating 90 degrees
        // moves what was on the LEFT edge to the TOP edge, so RED should end up on top and BLUE
        // on the bottom. Empirically confirmed direction via kotile:demo's rotationHarness before
        // writing this assertion, since the Y-up GL / Y-down content-space flip makes the sign
        // easy to get backwards by pure reasoning alone.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8) // left half
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8) // right half
            val texture = Texture(sheetPixmap)
            sheetPixmap.dispose()
            val region = TextureRegion(texture)

            val canvas = KotileCanvas(8, 8)
            canvas.begin()
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = 8f, h = 8f, rotationDeg = 90f)
            canvas.end()
            canvas.dispose()
            texture.dispose()
        }

        val top = pixels.averageColor(0, 0, 8, 4)
        top.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        top.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        val bottom = pixels.averageColor(0, 4, 8, 8)
        bottom.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        bottom.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)

        pixels.dispose()
    }

    test("drawSprite flipX mirrors the sprite horizontally (krogue-csc)").config(enabled = HeadlessGl.available) {
        // Same left(RED)/right(BLUE) split as the rotation test. Unflipped, RED is on the left;
        // flipX=true must swap them without a rotation.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8) // left half
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8) // right half
            val texture = Texture(sheetPixmap)
            sheetPixmap.dispose()
            val region = TextureRegion(texture)

            val canvas = KotileCanvas(8, 8)
            canvas.begin()
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = 8f, h = 8f, flipX = true)
            canvas.end()
            // Drawn again, unflipped this time: proves the first flipped draw didn't
            // permanently mutate the shared region instance.
            canvas.begin()
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = 8f, h = 8f)
            canvas.end()
            canvas.dispose()
            texture.dispose()
        }

        // The second (unflipped) draw wins on screen: RED back on the left.
        pixels.averageColor(0, 0, 4, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.averageColor(4, 0, 8, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("drawSprite flipX actually swaps sides while flipped (krogue-csc)").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8) // left half
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8) // right half
            val texture = Texture(sheetPixmap)
            sheetPixmap.dispose()
            val region = TextureRegion(texture)

            val canvas = KotileCanvas(8, 8)
            canvas.begin()
            canvas.drawSprite(pxX = 0f, pxY = 0f, region = region, w = 8f, h = 8f, flipX = true)
            canvas.end()
            canvas.dispose()
            texture.dispose()
        }

        // flipX swaps sides: BLUE (was on the right) now shows on the left, RED on the right.
        pixels.averageColor(0, 0, 4, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.averageColor(4, 0, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("TileRenderer honors a StaticSpriteTile's flipX (krogue-csc)").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8)
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8)
            val file = File.createTempFile("kotile-flip", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
            sheetPixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0, flipX = true))
            renderer.render()
            renderer.dispose()
            canvas.dispose()
            sheet.dispose()
        }

        pixels.averageColor(0, 0, 4, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.averageColor(4, 0, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test("TileRenderer honors an AnimatedSpriteTile's flipX (krogue-csc)").config(enabled = HeadlessGl.available) {
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8)
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8)
            val file = File.createTempFile("kotile-flip2", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
            sheetPixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val renderer = SpriteTileRenderer(canvas, sheet)
            val tile = AnimatedSpriteTile(
                frames = listOf(AnimationFrame(sheet.region(0, 0), durationMs = 100)),
                flipX = true,
            )
            renderer.drawTile(0, 0, z = 0, tile = tile)
            renderer.render()
            renderer.dispose()
            canvas.dispose()
            sheet.dispose()
        }

        pixels.averageColor(0, 0, 4, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.averageColor(4, 0, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
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
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE)) // bg quads blue
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

    test("EffectsLayer threads an effect's rotationDeg through to drawSprite (krogue-m05)").config(
        enabled = HeadlessGl.available,
    ) {
        // Same left(RED)/right(BLUE) split as drawSprite's own rotation test, but spawned as an
        // Effect with rotationDeg = 90 instead of calling drawSprite directly — proves
        // EffectsLayer.render actually passes the field through rather than dropping it.
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
            val sheetPixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            sheetPixmap.blending = Pixmap.Blending.None
            sheetPixmap.setColor(Color.RED)
            sheetPixmap.fillRectangle(0, 0, 4, 8)
            sheetPixmap.setColor(Color.BLUE)
            sheetPixmap.fillRectangle(4, 0, 4, 8)
            val texture = Texture(sheetPixmap)
            sheetPixmap.dispose()

            val canvas = KotileCanvas(8, 8)
            val effects = EffectsLayer()
            val stack = LayerStack(canvas)
            stack.add(effects)

            effects.spawn(
                Effect(pxX = 0f, pxY = 0f, w = 8f, h = 8f, region = TextureRegion(texture), rotationDeg = 90f),
            )
            stack.render()

            canvas.dispose()
            texture.dispose()
        }

        pixels.averageColor(0, 0, 8, 4).r.toDouble() shouldBe (1.0 plusOrMinus 0.1) // RED now on top
        pixels.averageColor(0, 4, 8, 8).b.toDouble() shouldBe (1.0 plusOrMinus 0.1) // BLUE now on bottom
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
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
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
            window.drawTile(0, 0, StaticAsciiTile('Û', Color.WHITE, Color.CLEAR))
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

    // -------------------------------------------------------------------------
    // krogue-drk: whole-frame composite cache (ADR-0024)
    // -------------------------------------------------------------------------

    test("krogue-drk: AsciiTileWindow's composite cache reflects a clearTile, not stale content").config(
        enabled = HeadlessGl.available,
    ) {
        // Regression guard for the cache's FBO recomposite pass: it must clear before redrawing, or
        // a cell cleared after the first paint would keep showing its old (cached) color forever.
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 8
                heightInTiles = 4
            }
            window.drawTile(0, 0, StaticAsciiTile(' ', Color.WHITE, Color.RED))
            window.render() // first paint: cache created, cell (0,0) red
            window.clearTile(0, 0)
            window.render() // recomposite must drop the red, not retain it from the cache
            window.dispose()
        }
        pixels.averageColor(0, 0, 10, 10).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    // -------------------------------------------------------------------------
    // krogue-7va (ADR-0030): an ASCII cell's glyph and background resolve from different
    // layers -- glyph top-cell-wins, background from the top-most cell that paints one. These
    // supersede the ADR-0029 cases that pinned background as top-cell-wins too; that policy
    // is what made a transparent background do nothing. Pinned so it can't drift back.
    // -------------------------------------------------------------------------

    /** Renders one 10x10 ASCII cell: z=0 is always '#' white on blue; [overlay] goes on z=1. */
    fun asciiOverlayCell(overlay: StaticAsciiTile?): Color {
        val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
            val window = AsciiTileWindow.create {
                widthInTiles = 1
                heightInTiles = 1
            }
            window.drawTile(0, 0, z = 0, tile = StaticAsciiTile('#', Color.WHITE, Color.BLUE))
            if (overlay != null) window.drawTile(0, 0, z = 1, tile = overlay)
            window.render()
            window.dispose()
        }
        val avg = pixels.averageColor(0, 0, 10, 10)
        pixels.dispose()
        return avg
    }

    test("krogue-7va: a CLEAR-background ASCII cell keeps the background of the cell beneath it").config(
        enabled = HeadlessGl.available,
    ) {
        // The point of per-channel resolution (ADR-0030): an overlay sits ON the terrain instead of
        // erasing it. The '@' takes the glyph channel; z=0's blue still supplies the background,
        // without the creature cell having to know what colour the floor is.
        val baseline = asciiOverlayCell(null)
        baseline.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)

        // Averaged over the whole cell the blue lands near 0.72, not 1.0, because the '@' glyph
        // itself covers roughly a quarter of the cell in red. Asserting "most of the cell is still
        // blue" rather than a tuned constant, and pinning the contrast against BLACK below.
        val overlaid = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.CLEAR))
        overlaid.b.toDouble() shouldBeGreaterThan 0.5 // z=0's background survived under the glyph
        overlaid.r.toDouble() shouldBeGreaterThan 0.1 // ...and the '@' drew on top of it
    }

    test("krogue-7va: a transparent ASCII background is now distinguishable from an opaque black one").config(
        enabled = HeadlessGl.available,
    ) {
        // The inverse of what ADR-0029 measured and pinned. CLEAR used to be byte-identical to
        // BLACK once layered, which is what made StaticAsciiTile's KDoc a lie; now CLEAR defers to
        // the layer below and BLACK paints black, so they must differ.
        val clear = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.CLEAR))
        val black = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.BLACK))
        clear.b.toDouble() shouldBeGreaterThan 0.5 // defers to z=0's blue
        black.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // paints its own black
    }

    test("krogue-7va: an opaque ASCII background still wins outright, hiding the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // Per-channel is not alpha blending: the first cell that paints a background wins and its
        // colour is used as-is. An opaque overlay must still hide the terrain colour completely.
        val opaque = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.GREEN))
        opaque.g.toDouble() shouldBeGreaterThan 0.5 // the overlay's green
        opaque.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // z=0's blue is gone
    }

    test("krogue-7va: a blank ASCII cell still wins the glyph channel, but not the background").config(
        enabled = HeadlessGl.available,
    ) {
        // The remaining footgun, now narrower than it was: a space is keyed out and draws nothing,
        // and it still takes the GLYPH channel (so '#' below is hidden) -- but the background below
        // now shows through, where before the whole cell went black. clearTile still beats a space.
        val blanked = asciiOverlayCell(StaticAsciiTile(' ', Color.WHITE, Color.CLEAR))
        blanked.b.toDouble() shouldBe (1.0 plusOrMinus 0.15) // z=0's background survives in full...
        blanked.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // ...but z=0's white '#' is not drawn
    }

    test("krogue-7va: a background-only overlay must sit below what it tints, not above").config(
        enabled = HeadlessGl.available,
    ) {
        // Pins the layer ordering the KDoc and ADR-0030 recommend, because the first draft of both
        // claimed the opposite and was wrong: a highlight ABOVE a creature still wins the glyph
        // channel (a space is a glyph, just a keyed-out one) and erases it.
        fun cellOf(vararg layers: StaticAsciiTile): Color {
            val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
                val window = AsciiTileWindow.create {
                    widthInTiles = 1
                    heightInTiles = 1
                }
                layers.forEachIndexed { z, tile -> window.drawTile(0, 0, z = z, tile = tile) }
                window.render()
                window.dispose()
            }
            val avg = pixels.averageColor(0, 0, 10, 10)
            pixels.dispose()
            return avg
        }

        val terrain = StaticAsciiTile('#', Color.WHITE, Color.BLUE)
        val creature = StaticAsciiTile('@', Color.RED, Color.CLEAR)
        val highlight = StaticAsciiTile(' ', Color.WHITE, Color.GREEN)

        // Recommended: terrain, highlight, creature. Creature keeps the glyph, highlight the bg.
        val correct = cellOf(terrain, highlight, creature)
        correct.r.toDouble() shouldBeGreaterThan 0.1 // the creature is visible...
        correct.g.toDouble() shouldBeGreaterThan 0.4 // ...on the highlight's tint...
        correct.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // ...which replaced the terrain's blue

        // The tempting-but-wrong order: highlight on top blanks the creature entirely.
        val wrong = cellOf(terrain, creature, highlight)
        wrong.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // no creature at all
    }

    test("krogue-7va: a dynamic background under a static glyph keeps animating through a viewport").config(
        enabled = HeadlessGl.available,
    ) {
        // The regression guard for this PR's other half. render(source, viewport) decides a cell is
        // animated by asking whether ANY layer there holds a DynamicAsciiTile -- it used to ask only
        // about the top cell. Now that a background can come from underneath, a dynamic cell below a
        // static glyph changes the cell every frame while never being the top cell: under the old
        // predicate it would be marked clean and freeze. Two renders at different elapsed times must
        // therefore produce different backgrounds.
        val flickering = AnimatedAsciiTile(
            frames = listOf(
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.BLUE), durationMs = 100),
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.GREEN), durationMs = 100),
            ),
        )

        fun frameAt(elapsedMs: Long): Color {
            val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
                val window = AsciiTileWindow.create {
                    widthInTiles = 1
                    heightInTiles = 1
                }
                val source = LayeredTilemap<AsciiTile>(1, 1)
                source.setCell(0, 0, 0, flickering) // dynamic BACKGROUND, underneath...
                source.setCell(0, 0, 1, StaticAsciiTile('@', Color.RED, Color.CLEAR)) // ...a static glyph
                window.render(source, elapsedMs = 0) // first paint establishes the cache
                window.render(source, elapsedMs = elapsedMs)
                window.dispose()
            }
            val avg = pixels.averageColor(0, 0, 10, 10)
            pixels.dispose()
            return avg
        }

        val frame0 = frameAt(0) // blue frame
        val frame1 = frameAt(150) // green frame

        frame0.b.toDouble() shouldBeGreaterThan 0.5 // the dynamic background is showing...
        frame1.g.toDouble() shouldBeGreaterThan 0.5 // ...and it advanced rather than freezing
        frame1.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)
    }

    test("krogue-7va: both paths let an upper layer that paints nothing reveal the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // Same setup on both paths, asserted side by side. They still resolve differently by
        // construction (sprite alpha-blends pixels; ASCII picks a background layer per channel),
        // but the consumer-visible promise now agrees: an upper layer that paints nothing does not
        // erase what is under it. See ADR-0029 for the divergence, ADR-0030 for the convergence.
        val sprite = renderLayered(background = Color.BLUE, foreground = Color(1f, 0f, 0f, 0f))
        sprite.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)

        val ascii = asciiOverlayCell(StaticAsciiTile(' ', Color.WHITE, Color.CLEAR))
        ascii.b.toDouble() shouldBe (1.0 plusOrMinus 0.15)
    }

    // -------------------------------------------------------------------------
    // krogue-s5h: a consumer's own FrameBuffer must survive kotile rendering. libGDX FBOs
    // do not nest -- FrameBuffer.end() binds 0 unconditionally -- so the composite cache
    // used to silently steal it and put the frame on screen instead, with no error.
    // -------------------------------------------------------------------------

    test("krogue-s5h: rendering inside a consumer's own FrameBuffer leaves it bound, and lands in it").config(
        enabled = HeadlessGl.available,
    ) {
        // The consumer case this protects: render kotile into your own FBO to post-process it,
        // render to a texture, do a transition, take a screenshot. Drive an AsciiTileWindow inside
        // a caller-owned FBO and read that FBO back -- if the cache steals the binding, the content
        // goes to the window and this reads an empty buffer.
        var boundAfterFirst = -1
        var boundAfterCacheHit = -1
        var boundAfterRealloc = -1
        var callerHandle = -1
        // (before, after) of the GL viewport around each render call.
        var viewportsAroundRenders = listOf<Pair<List<Int>, List<Int>>>()
        var pixelsInCallerFbo: Color? = null

        // The Pixmap HeadlessGl returns is deliberately unused here (hence the bare dispose): this
        // test's own `consumerFbo.end()` binds framebuffer 0, so that capture reads the shared
        // window rather than HeadlessGl's capture FBO. Harmless -- every assertion below comes from
        // the closure vars, captured while the right buffer was bound -- but don't add an assertion
        // on the returned pixmap here without rebinding first.
        HeadlessGl.render(20, 20, Color.BLACK) {
            val query = BufferUtils.newIntBuffer(16)
            fun frameBufferBinding(): Int {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query)
                return query.get(0)
            }
            fun viewport(): IntArray {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query)
                return IntArray(4) { query.get(it) }
            }

            val consumerFbo = FrameBuffer(Pixmap.Format.RGBA8888, 20, 20, false)
            consumerFbo.begin()
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            callerHandle = consumerFbo.framebufferHandle

            val window = AsciiTileWindow.create {
                widthInTiles = 2
                heightInTiles = 2
                fitToWindow = false // so resize() below reallocates rather than reflowing to nothing
            }
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
            // Three passes, because they hit different code: the first ALLOCATES the cache's
            // FrameBuffer (whose constructor leaves 0 bound), the second is a pure cache hit
            // (nothing dirty, so no FBO pass at all), and the resize DISPOSES and reallocates.
            // Each must leave the caller's binding and viewport exactly as it found them.
            //
            // Note what is compared: the viewport around EACH render, not every render against
            // the original. A resize is *supposed* to change the viewport -- that is what resize
            // means -- so asserting pass 3 against the pre-resize value would assert the opposite
            // of the intended behaviour (and did, briefly: CI caught it).
            fun renderPreservingState(): Int {
                val before = viewport().toList()
                window.render()
                viewportsAroundRenders += before to viewport().toList()
                return frameBufferBinding()
            }

            boundAfterFirst = renderPreservingState()

            // Did the blue actually land in the caller's buffer? Read it explicitly:
            // createFromFrameBuffer reads whatever is bound, so reading blind would happily report
            // the window's contents and pass even when the binding was stolen. Checked here, after
            // the first render, while the grid still matches this 20x20 buffer.
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, callerHandle)
            val inside = Pixmap.createFromFrameBuffer(0, 0, 20, 20)
            pixelsInCallerFbo = inside.averageColor(0, 0, 20, 20)
            inside.dispose()

            boundAfterCacheHit = renderPreservingState()

            window.resize(40, 40)
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
            boundAfterRealloc = renderPreservingState()

            consumerFbo.end()
            window.dispose()
            consumerFbo.dispose()
        }.dispose()

        boundAfterFirst shouldBe callerHandle // survived the allocating first render...
        boundAfterCacheHit shouldBe callerHandle // ...the cache hit that does no FBO work...
        boundAfterRealloc shouldBe callerHandle // ...and the resize that disposes and reallocates
        // Every render left the viewport as it found it -- checked per render, so a pass that
        // transiently clobbers it can't hide behind a later pass that happens to look right.
        viewportsAroundRenders shouldHaveSize 3
        viewportsAroundRenders.forEach { (before, after) -> after shouldBe before }
        pixelsInCallerFbo!!.b.toDouble() shouldBe (1.0 plusOrMinus 0.15) // the frame went where the caller asked
    }

    test("krogue-s5h: a throwing draw callback still leaves the caller's framebuffer bound").config(
        enabled = HeadlessGl.available,
    ) {
        // The restore has to survive the unhappy path too. If a consumer's own code throws
        // mid-recomposite -- here a regionFor() that blows up between fbo.begin() and fbo.end() --
        // libGDX never runs its end(), so kotile's internal cache FBO is still bound. Restoring
        // only "when someone else's buffer was bound" would leave that cache FBO bound for good,
        // and every later draw in the app would silently land inside it.
        var boundBeforeThrow = -1
        var boundAfterThrow = -2
        var threw = false

        HeadlessGl.render(16, 16, Color.BLACK) {
            val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(Color.RED)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-s5h-throw", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val exploding = object : TileRenderer(canvas) {
                override fun regionFor(staticTile: StaticSpriteTile): TextureRegion =
                    error("boom -- a consumer's region lookup failed mid-recomposite")
            }
            exploding.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))

            val query = BufferUtils.newIntBuffer(16)
            fun binding(): Int {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query)
                return query.get(0)
            }

            // Bind framebuffer 0 explicitly. This is the whole point of the test: the bug lived in
            // an `if (handle != 0)` skip, so it only ever bit when nothing of the caller's was
            // bound. HeadlessGl.render has its own capture FBO bound around draw(), which would
            // make handle non-zero and let the buggy code restore correctly -- the test would pass
            // against the very code it exists to catch.
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
            boundBeforeThrow = binding()
            try {
                exploding.render()
            } catch (expected: IllegalStateException) {
                threw = true
            }
            boundAfterThrow = binding()

            exploding.dispose()
            canvas.dispose()
            sheet.dispose()
        }.dispose()

        threw shouldBe true // the consumer's exception propagated, as it should
        boundBeforeThrow shouldBe 0 // guards the guard: if this is ever non-zero the test is toothless
        // ...and framebuffer 0 is bound again afterwards, rather than kotile's cache FBO being left
        // behind. This is the assertion the old `if (handle != 0)` skip fails: it leaves the cache
        // FBO bound (measured: 0 -> 1), silently redirecting every later draw into the cache.
        boundAfterThrow shouldBe boundBeforeThrow
    }

    // -------------------------------------------------------------------------
    // krogue-1qb: pixel coverage for AsciiTileWindow.clear/clearLayer -- the mirrors of the
    // sprite cases added in krogue-0y8. Both carry the same "collect the vacated cells, then
    // mark them dirty" bookkeeping, and it was only pixel-tested on the sprite side.
    // -------------------------------------------------------------------------

    test("krogue-1qb: AsciiTileWindow.clear drops every layer, leaving no stale cache content").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val pixels = HeadlessGl.render(10, 10, Color.BLACK) {
            val window = AsciiTileWindow.create {
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

    test("krogue-drk: AsciiTileWindow renders identical output across repeated render calls with no writes between them").config(
        enabled = HeadlessGl.available,
    ) {
        // The cache-hit path (dirty == false) must still reproduce the same frame, not blank/stale.
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val tile = AnimatedAsciiTile(
            frames = listOf(
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.RED), durationMs = 100),
                AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.GREEN), durationMs = 100),
            ),
        )
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val custom = object : DynamicAsciiTile {
            override fun resolveAt(elapsedMs: Long): StaticAsciiTile =
                StaticAsciiTile(' ', Color.WHITE, if (elapsedMs < 100) Color.RED else Color.GREEN)
        }
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels = HeadlessGl.render(16, 16, Color.BLACK) {
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
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels = HeadlessGl.render(16, 8, Color.BLACK) {
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

    test("krogue-drk: TileRenderer keeps an AnimatedSpriteTile animating across renders with no writes between them").config(
        enabled = HeadlessGl.available,
    ) {
        val pixels = HeadlessGl.render(8, 8, Color.BLACK) {
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
            val shimmering = AnimatedSpriteTile(
                frames = listOf(
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
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        val pixels = HeadlessGl.render(80, 40, Color.BLACK) {
            val window = AsciiTileWindow.create {
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
        renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
        renderer.render()
        renderer.dispose()
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
                renderer.drawTile(x, y, z = 0, tile = StaticSpriteTile(sheetX = 0, sheetY = 0, tint = tint))
            }
        }
        renderer.render()
        renderer.dispose()
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
                renderer.drawTile(x, y, z = 0, tile = StaticSpriteTile(sheetX = 0, sheetY = 0))
                renderer.drawTile(x, y, z = 1, tile = StaticSpriteTile(sheetX = 0, sheetY = 1))
            }
        }
        renderer.render()
        renderer.dispose()
        canvas.dispose()
        sheet.dispose()
    }
    val avg = pixels.averageColor(0, 0, 64, 64)
    pixels.dispose()
    return avg
}
