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
 * Tile coordinates have their origin at the top-left with y increasing
 * downwards; this is mapped onto the GPU's bottom-left origin internally. Draw
 * calls must be made between [begin] and [end]. Instances own GPU resources and
 * must be [dispose]d.
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
