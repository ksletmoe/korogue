import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import java.util.zip.Deflater

/**
 * One-off empirical check for krogue-m05 (kotile sprite rotation): draws a four-quadrant
 * (RED top-left, GREEN top-right, BLUE bottom-left, YELLOW bottom-right) sprite at 0/90/180/270
 * via [KotileCanvas.drawSprite]'s new `rotationDeg`, so the rotation direction/sign convention
 * can be confirmed by eye against the doc comment's claim ("positive = clockwise on screen")
 * before trusting it in a headless-GL assertion (this machine has no DISPLAY, so those are
 * skipped locally — this harness is the only way to actually see the result here).
 *
 *   ./gradlew :kotile:demo:rotationHarness   # -> demo/build/rotation-harness.png
 */
private const val WINDOW_W_PX = 400
private const val WINDOW_H_PX = 120

private class RotationHarness(private val outPath: String) : ApplicationAdapter() {
    private lateinit var canvas: KotileCanvas
    private lateinit var texture: Texture
    private var frame = 0

    override fun create() {
        canvas = KotileCanvas(80, 80)
        texture = quadrantTexture()
    }

    override fun render() {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        canvas.begin()
        val region = TextureRegion(texture)
        listOf(0f, 90f, 180f, 270f).forEachIndexed { i, angle ->
            canvas.drawSprite(pxX = 20f + i * 90f, pxY = 20f, region = region, w = 80f, h = 80f, rotationDeg = angle)
        }
        canvas.end()

        if (++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("ROTATION HARNESS wrote $outPath")
            Gdx.app.exit()
        }
    }

    override fun dispose() {
        canvas.dispose()
        texture.dispose()
    }

    private fun quadrantTexture(): Texture {
        val pixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        pixmap.setColor(Color.RED)
        pixmap.fillRectangle(0, 0, 4, 4) // top-left (source-image convention: row 0 = top)
        pixmap.setColor(Color.GREEN)
        pixmap.fillRectangle(4, 0, 4, 4) // top-right
        pixmap.setColor(Color.BLUE)
        pixmap.fillRectangle(0, 4, 4, 4) // bottom-left
        pixmap.setColor(Color.YELLOW)
        pixmap.fillRectangle(4, 4, 4, 4) // bottom-right
        return Texture(pixmap).also { pixmap.dispose() }
    }
}

fun main() {
    val outPath = System.getProperty("kotile.harness.out")
        ?: "${System.getProperty("user.dir")}/rotation-harness.png"

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("kotile rotation harness")
        setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
        disableAudio(true)
    }
    Lwjgl3Application(RotationHarness(outPath), config)
}
