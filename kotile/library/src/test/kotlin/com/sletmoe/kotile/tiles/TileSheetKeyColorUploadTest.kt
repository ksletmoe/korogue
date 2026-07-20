package com.sletmoe.kotile.tiles

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.HeadlessGl
import com.sletmoe.kotile.averageColor
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

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
 * Both are fixed by keying through [com.badlogic.gdx.graphics.Pixmap.drawPixel] (libGDX's own path)
 * and only *reading* the buffer, with indexed reads that never move the position.
 *
 * Unlike the NPOT sub-region bug (see [NpotTileSheetTest], which needs a dimension assertion because
 * Mesa samples NPOT correctly), both of these show up in the rendered pixels on **any** GL — so these
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
            val avg = pixels.averageColor(2, 2, tile - 2, tile - 2)
            avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
            avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            pixels.dispose()
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
            val avg = pixels.averageColor(1, 2, tile - 1, tile - 2)
            avg.r.toDouble() shouldBe (avg.g.toDouble() plusOrMinus 0.05)
            avg.r.toDouble() shouldBe (avg.b.toDouble() plusOrMinus 0.05)
            pixels.dispose()
        }
})
