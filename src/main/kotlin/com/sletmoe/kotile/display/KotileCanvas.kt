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
 * downwards; this maps onto the GPU's bottom-left origin internally.
 */
class KotileCanvas(val tileWidthPx: Int, val tileHeightPx: Int) : Disposable {
    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()

    val widthPx: Int get() = Gdx.graphics.width
    val heightPx: Int get() = Gdx.graphics.height

    val width: Int get() = widthPx / tileWidthPx
    val height: Int get() = heightPx / tileHeightPx

    init {
        resize(widthPx, heightPx)
    }

    fun resize(widthPx: Int, heightPx: Int) {
        camera.setToOrtho(false, widthPx.toFloat(), heightPx.toFloat())
        batch.projectionMatrix = camera.combined
    }

    fun begin() = batch.begin()

    fun end() = batch.end()

    fun drawTile(x: Int, y: Int, region: TextureRegion, tint: Color = Color.WHITE) {
        batch.color = tint
        val screenY = (heightPx - (y + 1) * tileHeightPx).toFloat()
        batch.draw(region, (x * tileWidthPx).toFloat(), screenY, tileWidthPx.toFloat(), tileHeightPx.toFloat())
    }

    override fun dispose() = batch.dispose()
}
