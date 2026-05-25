import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import java.util.zip.Deflater

private val BACKGROUND: Color = Color.valueOf("1d1f21ff")

class KotileDemo : ApplicationAdapter() {
    private lateinit var window: AsciiTileWindow
    private val snapshotPath: String? = System.getProperty("kotile.snapshot")
    private var frame = 0

    override fun create() {
        window = AsciiTileWindow.create {
            widthInTiles = 80
            heightInTiles = 30
        }

        window.fill(AsciiTileDescriptor(' ', Color.WHITE, BACKGROUND))
        "Hello, kotile!".forEachIndexed { i, character ->
            window.drawTile(2 + i, 1, AsciiTileDescriptor(character, Color.GREEN, BACKGROUND))
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        window.render()

        if (snapshotPath != null && ++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(snapshotPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            Gdx.app.exit()
        }
    }

    override fun resize(width: Int, height: Int) = window.resize(width, height)

    override fun dispose() = window.dispose()
}

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("kotile")
        setWindowedMode(800, 300)
    }
    Lwjgl3Application(KotileDemo(), config)
}
