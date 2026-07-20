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
 * Regression guard for krogue-3wr: [TileSheet]'s `keyColor` keyer (`zeroOutColor`) walked the
 * source [Pixmap]'s `ByteBuffer` with `get()` and left its position at the **end**. For a sheet
 * whose source is *already* a power of two — uploaded as-is (`upload = source`) rather than copied
 * into a fresh padded pixmap — `Texture(pixmap)` then ran `glTexImage2D` from that end-positioned
 * buffer, uploading **zero bytes** and yielding a BLANK texture: every glyph rendered invisible.
 * (NPOT sheets escaped because they're copied into a fresh padded pixmap first.) That is exactly why
 * the bundled 8x8 (128²) and 16x16 (256²) fonts rendered blank while 10x10/12x12 (padded) were fine.
 * The fix rewinds the buffer after keying.
 *
 * Unlike the NPOT sub-region bug (see [NpotTileSheetTest], which needs a dimension assertion because
 * Mesa samples NPOT correctly), a zero-byte upload yields a blank texture on **any** GL — so the
 * pixel check below is a real regression guard on the Linux Mesa CI too.
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
                    val sheetPixmap = Pixmap(tile, tile, Pixmap.Format.RGBA8888)
                    sheetPixmap.setColor(Color.RED)
                    sheetPixmap.fill()
                    val file = File.createTempFile("kotile-keycolor-pot", ".png").apply { deleteOnExit() }
                    PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), sheetPixmap)
                    sheetPixmap.dispose()

                    val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), tile, tile, keyColor = Color.BLACK)
                    val canvas = KotileCanvas(tile, tile)
                    val renderer = SpriteTileRenderer(canvas, sheet)
                    renderer.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))
                    renderer.render()
                    renderer.dispose()
                    canvas.dispose()
                    sheet.dispose()
                }

            // Un-fixed: the upload is blank, so the cell keeps the black clear color. Fixed: solid RED.
            val avg = pixels.averageColor(2, 2, tile - 2, tile - 2)
            avg.r.toDouble() shouldBe (1.0 plusOrMinus 0.1)
            avg.g.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            avg.b.toDouble() shouldBe (0.0 plusOrMinus 0.1)
            pixels.dispose()
        }
})
