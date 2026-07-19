package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests driving real OpenGL rendering of single-cell ASCII and\n * sprite primitives through the public API: solid fills, tinted glyphs, sprite\n * draw/tint, margin/spacing, sub-tile [KotileCanvas.drawSprite] placement, and\n * the rotation/flip transforms. Skipped unless a (software) GL context is\n * available; see [HeadlessGl]. Shared helpers live in RenderingIntegrationTestSupport.
 */
class RenderingPrimitivesIntegrationTest : FunSpec({

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
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
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
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
                val shimmering =
                    AnimatedSpriteTile(
                        frames =
                            listOf(
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

        val pixelsFrame1 =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
                val shimmering =
                    AnimatedSpriteTile(
                        frames =
                            listOf(
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
        val pixels =
            HeadlessGl.render(8, 4, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(12, 12, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
                val tile =
                    AnimatedSpriteTile(
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
})
