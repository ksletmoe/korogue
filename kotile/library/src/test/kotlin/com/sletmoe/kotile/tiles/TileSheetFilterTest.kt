package com.sletmoe.kotile.tiles

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.sletmoe.kotile.HeadlessGl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Guards the [TileSheet] texture-filter setup behind krogue-4ni: mipmaps + a
 * trilinear min-filter for clean downscaling, with an unchanged nearest
 * mag-filter so upscaling stays crisp.
 *
 * The magnification filter must be `Nearest` in **both** modes — that is what
 * keeps 1x and every upscale pixel-crisp (the acceptance criterion "upscaling
 * behavior unchanged"). Only the minification filter changes with `useMipMaps`.
 *
 * GL-gated via [HeadlessGl] (needs a real context to upload a texture), so it
 * runs on Linux/CI and self-skips on the macOS dev box.
 */
class TileSheetFilterTest : FunSpec({

    fun writeSheet(): String {
        val pixmap = Pixmap(20, 20, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        val file = File.createTempFile("kotile-filter", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
        pixmap.dispose()
        return file.absolutePath
    }

    test("mipmapped sheet uses a trilinear min-filter and nearest mag-filter")
        .config(enabled = HeadlessGl.available) {
            var minFilter: TextureFilter? = null
            var magFilter: TextureFilter? = null

            // HeadlessGl.render just supplies a live GL context here.
            HeadlessGl.render(20, 20, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(writeSheet()), 10, 10, useMipMaps = true)
                val texture = sheet.region(0, 0).texture
                minFilter = texture.minFilter
                magFilter = texture.magFilter
                sheet.dispose()
            }.dispose()

            minFilter shouldBe TextureFilter.MipMapLinearLinear
            magFilter shouldBe TextureFilter.Nearest
        }

    test("non-mipmapped sheet stays nearest/nearest (legacy behaviour)")
        .config(enabled = HeadlessGl.available) {
            var minFilter: TextureFilter? = null
            var magFilter: TextureFilter? = null

            HeadlessGl.render(20, 20, Color.BLACK) {
                val sheet = TileSheet(Gdx.files.absolute(writeSheet()), 10, 10, useMipMaps = false)
                val texture = sheet.region(0, 0).texture
                minFilter = texture.minFilter
                magFilter = texture.magFilter
                sheet.dispose()
            }.dispose()

            minFilter shouldBe TextureFilter.Nearest
            magFilter shouldBe TextureFilter.Nearest
        }
})
