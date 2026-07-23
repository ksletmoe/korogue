package com.sletmoe.kotile.tiles

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.sletmoe.kotile.HeadlessGl
import com.sletmoe.kotile.averageColor
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Regression guards for two bugs in [TileSheet]'s `keyColor` keyer, both from mutating the source
 * [Pixmap]'s backing buffer directly:
 *  - **krogue-3wr (blank):** the keyer walked the buffer with relative `get()`, leaving its position
 *    at the end; an already-power-of-two sheet, uploaded as-is, then ran `glTexImage2D` from that
 *    end position and uploaded zero bytes — a blank texture. (NPOT sheets escaped: they're copied
 *    into a fresh padded pixmap.) That is why the bundled 8x8/16x16 fonts rendered blank while
 *    10x10/12x12 did not.
 *  - **krogue-9gu (green/teal tint):** writing the key-out through the buffer corrupted the red byte
 *    of the pixel *after* a keyed one, so white glyph pixels next to the keyed background rendered
 *    cyan on thin strokes.
 * Both are fixed by keying entirely through libGDX's coordinate API — [com.badlogic.gdx.graphics.Pixmap.getPixel]
 * to test each pixel and [com.badlogic.gdx.graphics.Pixmap.drawPixel] to clear it, addressed by (x, y) —
 * which never reads or writes the backing buffer's bytes and never moves its position.
 *
 * And a third, from the source's *format* rather than its buffer:
 *  - **krogue-7i8 (keying no-ops on an alpha-less sheet):** `Pixmap(FileHandle)` decodes a PNG with
 *    no alpha channel to [Pixmap.Format.RGB888], which has no alpha byte — so `drawPixel(x, y, 0)`
 *    only rewrote the (already-black) RGB and the keyed background uploaded fully opaque, hiding cell
 *    backgrounds behind an opaque glyph surround. The bundled 12x12/16x16/9x16 sheets are stored
 *    without alpha and hit it; 8x8/10x10 carry an alpha channel and keyed fine, which is why the
 *    symptom first looked tied to tile size. Fixed by promoting the source to RGBA8888 before keying
 *    ([TileSheet]'s `decodeForKeying`). Its guard test **must** author the fixture with a real
 *    alpha-less encoder ([ImageIO] `TYPE_INT_RGB`): [PixmapIO] always writes an alpha channel, so a
 *    fixture round-tripped through it decodes to RGBA8888 and cannot reproduce the bug — which is
 *    exactly why the two buffer-mutation guards above never caught it.
 *
 * Unlike the NPOT sub-region bug (see [NpotTileSheetTest], which needs a dimension assertion because
 * Mesa samples NPOT correctly), all of these show up in the rendered pixels on **any** GL — so these
 * checks are real regression guards on the Linux Mesa CI too.
 */
class TileSheetKeyColorUploadTest : FunSpec({

    test("a keyColor'd already-power-of-two sheet uploads a non-blank texture")
        .config(enabled = HeadlessGl.available) {
            // A 16x16 source is already a power of two, so TileSheet uploads it as-is (upload =
            // source) — the code path the bug lived on.
            val tile = 16

            val pixels =
                HeadlessGl.render(tile, tile, Color.BLACK) {
                    // A solid RED tile with NO key-color (black) pixels: keying changes nothing, but
                    // zeroOutColor still runs (keyColor != null) and — before the fix — still left
                    // the buffer at its end, blanking the as-is upload.
                    var sheetPixmap: Pixmap? = null
                    var sheet: TileSheet? = null
                    var canvas: KotileCanvas? = null
                    var renderer: SpriteTileRenderer? = null
                    try {
                        sheetPixmap =
                            Pixmap(tile, tile, Pixmap.Format.RGBA8888).apply {
                                setColor(Color.RED)
                                fill()
                            }
                        val file = File.createTempFile("kotile-keycolor-pot", ".png").apply { deleteOnExit() }
                        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)

                        sheet = TileSheet(Gdx.files.absolute(file.absolutePath), tile, tile, keyColor = Color.BLACK)
                        canvas = KotileCanvas(tile, tile)
                        renderer = SpriteTileRenderer(canvas, sheet)
                        renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
                        renderer.render()
                    } finally {
                        // Dispose every native resource even if setup/render/assert fails, so a
                        // failure can't leak GL state into later headless-GL tests.
                        renderer?.dispose()
                        canvas?.dispose()
                        sheet?.dispose()
                        sheetPixmap?.dispose()
                    }
                }

            // Un-fixed: the upload is blank, so the cell keeps the black clear color. Fixed: solid RED.
            try {
                val avg = pixels.averageColor(2, 2, tile - 2, tile - 2)
                avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
                avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            } finally {
                // Dispose the captured Pixmap even if an assertion fails, so it can't leak into the
                // shared headless-GL context that outlives this test.
                pixels.dispose()
            }
        }

    test("keying does not drop the red channel of pixels next to a keyed pixel (no green tint)")
        .config(enabled = HeadlessGl.available) {
            val tile = 16

            val pixels =
                HeadlessGl.render(tile, tile, Color.BLACK) {
                    // Vertical 1px stripes: even columns black (keyed out), odd columns white. Every
                    // white column sits immediately after a keyed one — the adjacency whose red byte
                    // used to be dropped, tinting white glyph pixels green/cyan (krogue-9gu).
                    var sheetPixmap: Pixmap? = null
                    var sheet: TileSheet? = null
                    var canvas: KotileCanvas? = null
                    var renderer: SpriteTileRenderer? = null
                    try {
                        sheetPixmap = Pixmap(tile, tile, Pixmap.Format.RGBA8888)
                        for (x in 0 until tile) {
                            sheetPixmap.setColor(if (x % 2 == 0) Color.BLACK else Color.WHITE)
                            sheetPixmap.fillRectangle(x, 0, 1, tile)
                        }
                        val file = File.createTempFile("kotile-keycolor-stripes", ".png").apply { deleteOnExit() }
                        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)

                        sheet = TileSheet(Gdx.files.absolute(file.absolutePath), tile, tile, keyColor = Color.BLACK)
                        canvas = KotileCanvas(tile, tile)
                        renderer = SpriteTileRenderer(canvas, sheet)
                        renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
                        renderer.render()
                    } finally {
                        renderer?.dispose()
                        canvas?.dispose()
                        sheet?.dispose()
                        sheetPixmap?.dispose()
                    }
                }

            // The white stripes are colourless, so red must come through at the same level as green.
            // The bug drops red to 0 while green/blue survive — avg.r would collapse toward 0.
            try {
                val avg = pixels.averageColor(1, 2, tile - 1, tile - 2)
                // First prove the stripes are actually visible: ~half the region is white, half is the
                // keyed-out (transparent → black clear) column, so green/blue average near 0.5. Without
                // this, the blank-upload bug (all channels 0) would satisfy the r≈g≈b comparisons below
                // and the test would pass against un-fixed code — a test that cannot fail.
                avg.g.toDouble() shouldBe (0.5 plusOrMinus 0.1)
                avg.b.toDouble() shouldBe (0.5 plusOrMinus 0.1)
                // Red must come through at the same level as green/blue; the bug collapses it toward 0.
                avg.r.toDouble() shouldBe (avg.g.toDouble() plusOrMinus 0.05)
                avg.r.toDouble() shouldBe (avg.b.toDouble() plusOrMinus 0.05)
            } finally {
                pixels.dispose()
            }
        }

    test("keying an alpha-less (RGB888) sheet still makes the keyed color transparent")
        .config(enabled = HeadlessGl.available) {
            val tile = 40

            val pixels =
                // Blue clear, and the keyed sheet is drawn straight over it with normal alpha blending —
                // this reproduces the actual symptom (a glyph's keyed surround sitting over a background):
                // a keyed-out (transparent) texel lets the BLUE through, an un-keyed opaque black one
                // hides it. Drawn with a bare SpriteBatch rather than a SpriteTileRenderer on purpose:
                // the renderer's authoritative REPLACE blit overwrites its whole rectangle, so a keyed
                // texel lands as (0,0,0,0) and reads rgb-black whether keying worked or not — an
                // rgb-only readback of that path cannot see this bug (which is why the buffer-mutation
                // guards above, all single-layer REPLACE, never did).
                HeadlessGl.render(tile, tile, Color.BLUE) {
                    var sheet: TileSheet? = null
                    var batch: SpriteBatch? = null
                    try {
                        // Author the fixture with an alpha-less encoder: ImageIO TYPE_INT_RGB writes a
                        // PNG with no alpha channel, which Pixmap(FileHandle) decodes to RGB888 — the
                        // format the bug lives on. PixmapIO would write an alpha channel (decoding back
                        // to RGBA8888) and could not reproduce it. Left half white (the glyph, kept),
                        // right half black (the background, keyed out).
                        val image = BufferedImage(tile, tile, BufferedImage.TYPE_INT_RGB)
                        for (y in 0 until tile) {
                            for (x in 0 until tile) {
                                image.setRGB(x, y, if (x < tile / 2) 0xFFFFFF else 0x000000)
                            }
                        }
                        val file = File.createTempFile("kotile-keycolor-rgb888", ".png").apply { deleteOnExit() }
                        ImageIO.write(image, "png", file)

                        sheet = TileSheet(Gdx.files.absolute(file.absolutePath), tile, tile, keyColor = Color.BLACK)
                        batch =
                            SpriteBatch().apply {
                                projectionMatrix =
                                    OrthographicCamera().apply {
                                        setToOrtho(false, tile.toFloat(), tile.toFloat())
                                        update()
                                    }.combined
                            }
                        batch.begin()
                        batch.draw(sheet.region(0, 0), 0f, 0f, tile.toFloat(), tile.toFloat())
                        batch.end()
                    } finally {
                        batch?.dispose()
                        sheet?.dispose()
                    }
                }

            try {
                // First prove the sheet actually rendered (not blank): the un-keyed left half is white.
                // Without this the keyed-half check below could pass against a wholly-broken upload —
                // a test that cannot fail.
                val glyph = pixels.averageColor(2, 2, tile / 2 - 2, tile - 2)
                glyph.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                glyph.g.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                glyph.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)

                // The keyed (black) half must be transparent, so the BLUE background shows through.
                // Un-fixed (RGB888 keying no-ops) it stays opaque black: blue collapses to 0 and this fails.
                val keyed = pixels.averageColor(tile / 2 + 2, 2, tile - 2, tile - 2)
                keyed.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)
                keyed.r.toDouble() shouldBe (0.0 plusOrMinus 0.1)
                keyed.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            } finally {
                pixels.dispose()
            }
        }
})
