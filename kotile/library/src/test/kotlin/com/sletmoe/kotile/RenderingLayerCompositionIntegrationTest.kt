package com.sletmoe.kotile

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.Effect
import com.sletmoe.kotile.rendering.EffectsLayer
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.Layer
import com.sletmoe.kotile.rendering.LayerStack
import com.sletmoe.kotile.rendering.PixelRect
import com.sletmoe.kotile.rendering.UiLayer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Integration tests for free-layer and [LayerStack] composition, [AsciiTileWindow.drawText],\n * and the whole-frame composite cache (krogue-drk): painter-order compositing,\n * [EffectsLayer] placement/rotation, and draw-path agreement. Skipped unless a\n * (software) GL context is available; see [HeadlessGl].
 */
class RenderingLayerCompositionIntegrationTest : FunSpec({
    test("drawTile and drawSprite agree for a cell-aligned draw").config(enabled = HeadlessGl.available) {
        // drawTile is sugar over drawSprite: drawing cell (1, 1) via each path
        // must produce identical pixels. Guards the sugar's cell→pixel mapping
        // (including the y-flip) against the primitive.
        fun renderVia(useSprite: Boolean): Pixmap =
            HeadlessGl.render(12, 12, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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

    test(
        "a grid renderer's asLayer composites under a free layer in one stack",
    ).config(enabled = HeadlessGl.available) {
        // AsciiTileWindow.asLayer draws the (blue-background) grid; a free red
        // sprite is stacked on top over the top-left cell. Proves a grid layer
        // and a free layer share one begin/end pass and overlay by draw order.
        val pixels =
            HeadlessGl.render(20, 20, Color.BLACK) {
                val font = Fonts.cp437_10x10()
                val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx) // 10x10
                val window =
                    AsciiTileWindow.createWithCanvas(canvas, font) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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
        val pixels =
            HeadlessGl.render(8, 8, Color.BLACK) {
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

    test(
        "UiLayer draws a widget above the grid at its pixel bounds; hidden widgets don't draw",
    ).config(enabled = HeadlessGl.available) {
        // A blue-background 2x2 grid (20x20px) with a UiLayer on top. A visible
        // red widget occupies content pixels [2,8) x [2,8) — a free, sub-cell
        // rectangle straddling into cell (0,0). A second, hidden widget covers the
        // bottom-right cell; it must leave that cell showing the blue grid.
        val pixels =
            HeadlessGl.render(20, 20, Color.BLACK) {
                val font = Fonts.cp437_10x10()
                val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx) // 10x10
                val window =
                    AsciiTileWindow.createWithCanvas(canvas, font) {
                        widthInTiles = 2
                        heightInTiles = 2
                        fitToWindow = false // fixed 2x2 grid -> 20x20 px at integer 1x
                    }
                window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
                val red = solidTexture(Color.RED)

                val ui = UiLayer()
                ui.add(SolidWidget(PixelRect(2f, 2f, 6f, 6f), red)) // visible, on top
                ui.add(SolidWidget(PixelRect(10f, 10f, 10f, 10f), red, visible = false)) // hidden

                val stack = LayerStack(canvas)
                stack.add(window.asLayer()) // grid, below
                stack.add(ui) // UI, on top
                stack.render()

                ui.widgetCount shouldBe 2
                window.dispose()
                red.dispose()
                canvas.dispose()
                font.dispose()
            }

        // visible widget: red, over the grid
        pixels.averageColor(2, 2, 8, 8).r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        // outside its bounds: blue grid shows
        pixels.averageColor(0, 0, 2, 2).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        // hidden widget: blue grid, not red
        pixels.averageColor(12, 12, 20, 20).b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        pixels.averageColor(12, 12, 20, 20).r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
        pixels.dispose()
    }

    test(
        "sprite layers composite bottom-up: a transparent foreground reveals the background",
    ).config(enabled = HeadlessGl.available) {
        // Background terrain (blue, z=0) under a fully transparent foreground
        // sprite (z=1). Bottom-up compositing draws the background beneath the
        // foreground, so the blue shows through; the old top-cell-only path
        // would have drawn only the (invisible) foreground, leaving the black
        // clear color.
        val avg = renderLayered(background = Color.BLUE, foreground = Color(1f, 0f, 0f, 0f))
        avg.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        avg.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }

    test(
        "sprite layers composite bottom-up: an opaque foreground occludes the background",
    ).config(enabled = HeadlessGl.available) {
        // An opaque foreground (red, z=1) fully covers the background (blue,
        // z=0): compositing must not let occluded terrain bleed through.
        val avg = renderLayered(background = Color.BLUE, foreground = Color.RED)
        avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
        avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
    }

    test(
        "sprite tiles smooth at a fractional scale but stay crisp at an integer scale",
    ).config(enabled = HeadlessGl.available) {
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

    test(
        "a fractional-scaled glyph grid still renders through the sharp-bilinear path",
    ).config(enabled = HeadlessGl.available) {
        // Smoke test for the glyph layer on the same shader path: a full-block
        // glyph on a FitScale fixed grid at a non-integer scale must still fill
        // its cell (the shader must not blank or corrupt the glyph).
        val pixels =
            HeadlessGl.render(20, 20, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
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
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
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
        val pixels =
            HeadlessGl.render(80, 40, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
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
})
