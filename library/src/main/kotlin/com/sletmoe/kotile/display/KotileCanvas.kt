package com.sletmoe.kotile.display

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/**
 * Draws tile-sized texture regions onto the screen via a batched [SpriteBatch].
 *
 * ## Coordinate system
 *
 * **Tile coordinates** use a **top-left origin**: tile (0, 0) is the top-left
 * cell of the canvas, x increases rightward, and y increases downward.
 *
 * Internally, tile (x, y) is mapped to GL screen coordinates (bottom-left
 * origin, y up) before drawing. This mapping is an implementation detail and
 * is not visible to callers of [drawTile].
 *
 * **Mouse pixel coordinates** from `Gdx.input` (via libGDX Desktop /
 * `InputProcessor`) also use a top-left origin (0, 0 = top-left of window, y
 * increases downward). They are therefore in the same axis orientation as tile
 * coordinates and do **not** need a y-axis flip before pixel-to-tile division.
 * See [com.sletmoe.kotile.input.pixelToTile] for the conversion.
 *
 * Draw calls must be made between [begin] and [end]. Instances own GPU
 * resources and must be [dispose]d.
 *
 * @property tileWidthPx on-screen width of a tile, in pixels
 * @property tileHeightPx on-screen height of a tile, in pixels
 */
class KotileCanvas(val tileWidthPx: Int, val tileHeightPx: Int) : Disposable {
    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()

    /** Current drawable width in pixels (the application's framebuffer width). */
    val widthPx: Int get() = Gdx.graphics.width

    /** Current drawable height in pixels (the application's framebuffer height). */
    val heightPx: Int get() = Gdx.graphics.height

    /** Number of whole tiles that fit across [widthPx]. */
    val width: Int get() = widthPx / tileWidthPx

    /** Number of whole tiles that fit down [heightPx]. */
    val height: Int get() = heightPx / tileHeightPx

    init {
        resize(widthPx, heightPx)
    }

    /**
     * Updates the projection to a [widthPx] x [heightPx] viewport. Call this
     * from the application's resize callback.
     */
    fun resize(widthPx: Int, heightPx: Int) {
        camera.setToOrtho(false, widthPx.toFloat(), heightPx.toFloat())
        batch.projectionMatrix = camera.combined
    }

    /** Begins a batch of [drawTile] calls. */
    fun begin() = batch.begin()

    /** Ends the current batch, flushing it to the screen. */
    fun end() = batch.end()

    /**
     * Draws [region] in the cell at column [x], row [y] (top-left origin),
     * scaled to the tile size and multiplied by [tint] (white = unchanged).
     * Must be called between [begin] and [end].
     */
    fun drawTile(x: Int, y: Int, region: TextureRegion, tint: Color = Color.WHITE) {
        batch.color = tint
        val screenY = (heightPx - (y + 1) * tileHeightPx).toFloat()
        batch.draw(region, (x * tileWidthPx).toFloat(), screenY, tileWidthPx.toFloat(), tileHeightPx.toFloat())
    }

    /** Disposes the underlying sprite batch. */
    override fun dispose() = batch.dispose()
}
