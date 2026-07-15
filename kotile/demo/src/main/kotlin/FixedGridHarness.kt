import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.IntegerScale
import java.util.zip.Deflater

/**
 * Controlled visual harness for **fixed-grid** mode — the path the reflow demo
 * can't exercise. It validates the [com.sletmoe.kotile.rendering.GridViewport]
 * work (krogue-3eu): scaling, centering/letterbox, and the glViewport hard-clip.
 *
 * Everything is parameterised so several cases can be snapshotted from the same
 * retina box without touching code:
 *
 *   ./gradlew :kotile:demo:fixedGridHarness \
 *       -PoutFile=/tmp/case.png \
 *       -PwinW=850 -PwinH=430 -Pcols=40 -Prows=20 -Ppolicy=integer
 *
 * The scene is a **solid full-block (Û) ring** around the entire grid edge with
 * bright per-corner colors, plus a labelled interior. That makes the three
 * things under test unmistakable:
 *  - **Centering/letterbox:** the block ring sits inset from the window edges by
 *    equal margins; the bars are the clear color (dark blue) — if a partial tile
 *    bled into a bar it would show as a stray block.
 *  - **Hard-clip overflow:** with a grid larger than the window at 1x, the ring
 *    is cleanly cut at the content edge rather than smeared.
 *  - **Scaling/crispness:** IntegerScale gives crisp NN upscaling; FitScale
 *    gives a fractional fill whose edges must still be clipped, not bled.
 *
 * The PNG is written flipY=true (right-side up). Backbuffer-sized, so on a 2x
 * retina panel an 850x430 window yields a 1700x860 image.
 */
private class FixedGridHarness(
    private val outPath: String,
    private val cols: Int,
    private val rows: Int,
    private val policyName: String,
) : ApplicationAdapter() {
    private lateinit var window: AsciiTileWindow
    private var frame = 0

    override fun create() {
        val policy = if (policyName.equals("fit", ignoreCase = true)) FitScale else IntegerScale
        window =
            AsciiTileWindow.create {
                widthInTiles = cols
                heightInTiles = rows
                fitToWindow = false // fixed-grid: scale + center + letterbox
                scalePolicy = policy
            }
        buildScene()
    }

    private fun buildScene() {
        // Interior floor so the whole content rect is covered and any clip is
        // visible against the dark-blue clear color of the bars.
        window.fill(StaticAsciiTile('.', Color.DARK_GRAY, Color.BLACK))

        // Solid full-block ring (CP437 0xDB = 'Û') around the extreme edge. If
        // the content rect is centered correctly this ring frames a clean inset
        // rectangle; if a partial tile bleeds past the glViewport it lands in a
        // letterbox bar and is obvious.
        val corners = listOf(Color.RED, Color.GREEN, Color.CYAN, Color.YELLOW)
        for (x in 0 until cols) {
            block(x, 0, edgeColor(x, 0, corners))
            block(x, rows - 1, edgeColor(x, rows - 1, corners))
        }
        for (y in 0 until rows) {
            block(0, y, edgeColor(0, y, corners))
            block(cols - 1, y, edgeColor(cols - 1, y, corners))
        }

        window.drawText(2, 2, "FIXED-GRID ${cols}x$rows  ${policyName.uppercase()}", Color.WHITE, Color.BLACK)
        window.drawText(2, 4, "ring should frame a clean inset; bars = clear color", Color.LIME, Color.BLACK)
    }

    /** Corner cells take a distinct color so orientation and clipping are readable. */
    private fun edgeColor(x: Int, y: Int, corners: List<Color>): Color =
        when {
            x == 0 && y == 0 -> corners[0]
            x == cols - 1 && y == 0 -> corners[1]
            x == 0 && y == rows - 1 -> corners[2]
            x == cols - 1 && y == rows - 1 -> corners[3]
            else -> Color.GRAY
        }

    private fun block(x: Int, y: Int, color: Color) =
        window.drawTile(x, y, StaticAsciiTile('Û', color, Color.BLACK))

    override fun render() {
        // Distinct dark blue so letterbox bars are unmistakable vs black cells.
        Gdx.gl.glClearColor(0f, 0f, 0.25f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        window.render()

        if (++frame >= 2) {
            println(
                "FIXEDGRID grid=${window.widthInTiles}x${window.heightInTiles} nativeTilePx=${window.tileWidthPx}x${window.tileHeightPx} " +
                    "onScreenTilePx=${window.layout.tileWidthPx}x${window.layout.tileHeightPx} " +
                    "offsetPx=${window.layout.offsetXPx}x${window.layout.offsetYPx} " +
                    "logical=${Gdx.graphics.width}x${Gdx.graphics.height} backbuffer=${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}",
            )
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("FIXEDGRID wrote $outPath")
            Gdx.app.exit()
        }
    }

    override fun resize(width: Int, height: Int) = window.resize(width, height)

    override fun dispose() = window.dispose()
}

fun main() {
    val outPath = System.getProperty("kotile.harness.out")
        ?: "${System.getProperty("user.dir")}/fixed-grid-harness.png"
    val winW = System.getProperty("kotile.harness.winW")?.toInt() ?: 850
    val winH = System.getProperty("kotile.harness.winH")?.toInt() ?: 430
    val cols = System.getProperty("kotile.harness.cols")?.toInt() ?: 40
    val rows = System.getProperty("kotile.harness.rows")?.toInt() ?: 20
    val policy = System.getProperty("kotile.harness.policy") ?: "integer"

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile fixed-grid harness")
            setWindowedMode(winW, winH)
            disableAudio(true)
        }
    Lwjgl3Application(FixedGridHarness(outPath, cols, rows, policy), config)
}
