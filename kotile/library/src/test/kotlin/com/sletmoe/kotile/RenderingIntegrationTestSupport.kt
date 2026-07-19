package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.PixelRect
import com.sletmoe.kotile.rendering.ScalePolicy
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.rendering.Widget
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import java.io.File

// Shared helpers for the Rendering*IntegrationTest specs (same package, so no
// import needed). Each renders real pixels through a live GL context and must be
// called inside HeadlessGl.render. Extracted when the single ~1700-line
// RenderingIntegrationTest was split into focused specs (krogue-268).

/**
 * A 1x1 [color]-filled [Texture], stretched by [KotileCanvas.drawSprite] to any
 * size. Caller disposes. Must be created with a live GL context (inside
 * [HeadlessGl.render]).
 */
internal fun solidTexture(color: Color): Texture {
    val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
    pixmap.setColor(color)
    pixmap.fill()
    return Texture(pixmap).also { pixmap.dispose() }
}

/** A [Widget] that fills its content-pixel [bounds] with [texture] — enough to prove free-UI rendering. */
internal class SolidWidget(
    override val bounds: PixelRect,
    private val texture: Texture,
    override var visible: Boolean = true,
) : Widget {
    // Built once, not per render (mirrors the ButtonWidget fix in krogue-eea).
    private val region = TextureRegion(texture)

    override fun render(canvas: KotileCanvas) {
        canvas.drawSprite(bounds.x, bounds.y, region, bounds.width, bounds.height)
    }
}

internal val SHEET_BLUE = Color(0.3f, 0.3f, 0.9f, 1f)

/**
 * Renders a single 8x8 red/blue **checkerboard** sprite tile (a hard color edge
 * at every texel boundary) on a 1x1 fixed grid scaled to a [windowPx] window by
 * [policy], and returns the maximum "edge blend" found: `max over pixels of
 * min(r, b)`. A crisp (nearest) render yields ~0 (each pixel is pure red or pure
 * blue); a smoothed (sharp-bilinear) render yields a positive value where
 * adjacent texels blend to purple. The dense edges make the result robust to the
 * exact scale (a single centered edge can alias against the ~1px blend band).
 */
internal fun maxEdgeBlend(
    policy: ScalePolicy,
    windowPx: Int,
): Float {
    val pixels =
        HeadlessGl.render(windowPx, windowPx, Color.BLACK) {
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

internal fun renderSpriteTile(
    tileColor: Color,
    tint: Color,
): Color {
    val pixels =
        HeadlessGl.render(64, 64, Color.BLACK) {
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
internal fun renderLayered(
    background: Color,
    foreground: Color,
): Color {
    val pixels =
        HeadlessGl.render(64, 64, Color.BLACK) {
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
