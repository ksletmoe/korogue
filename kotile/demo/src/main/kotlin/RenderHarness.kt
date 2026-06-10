import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.AnimatableAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.TileViewport
import com.sletmoe.kotile.utilities.LayeredTilemap
import java.util.zip.Deflater

/**
 * Controlled visual harness for [AsciiTileWindow].
 *
 * It mirrors krogue's setup as closely as possible so we can decide whether a
 * rendering glitch lives in kotile or in krogue:
 *  - same window pixel size (800x400) and default bundled font (10x10), so the
 *    retina backbuffer is 1600x800 — the exact 2x upscale krogue hits;
 *  - same render path: `window.render(source, viewport, elapsedMs)` driven by a
 *    consumer-owned [LayeredTilemap] larger than the window plus a [TileViewport];
 *  - known content (text, every printable glyph, a foreground brightness ramp)
 *    so garbling, mis-slicing, or "too dark to see" failures are obvious.
 *
 * Run on macOS via the Gradle task (forks a JVM with -XstartOnFirstThread):
 *   ./gradlew :demo:renderHarness -PoutFile=/tmp/harness.png
 *
 * The PNG is written with `flipY = true`, so it is right-side up (unlike a raw
 * `ScreenUtils` framebuffer grab).
 */
private const val WINDOW_W_PX = 800
private const val WINDOW_H_PX = 400

/** Logical tile space, intentionally larger than the window to exercise viewport sampling. */
private const val MAP_W = 100
private const val MAP_H = 50

private class RenderHarness(private val outPath: String) : ApplicationAdapter() {
    private lateinit var window: AsciiTileWindow
    private val source = LayeredTilemap<AnimatableAsciiTile>(MAP_W, MAP_H)
    private var frame = 0

    override fun create() {
        window =
            AsciiTileWindow.create {
                widthInTiles = 80
                heightInTiles = 40
                fitToWindow = true
            }
        buildScene()
    }

    private fun buildScene() {
        val swatches =
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.LIGHT_GRAY)

        // Rows 0-1: BACKGROUND-QUAD path. A space glyph is transparent, so only
        // the 1x1 white background texture (tinted by the bg color) shows. Each
        // swatch is 4 cells wide. If THESE are bright but the glyph rows below
        // are dark, the darkening is glyph/font-texture specific.
        swatches.forEachIndexed { i, c ->
            repeat(4) { dx -> source.setCell(i * 4 + dx, 0, 1, AsciiTileDescriptor(' ', Color.BLACK, c)) }
            repeat(4) { dx -> source.setCell(i * 4 + dx, 1, 1, AsciiTileDescriptor(' ', Color.BLACK, c)) }
        }

        // Rows 3-4: GLYPH path at full coverage. Full-block (CP437 0xDB) tinted
        // with the same bright colors. If these are dark while the swatches
        // above are bright -> font/glyph tinting is the problem. If BOTH are
        // dark -> the darkening is global (gamma / blend / projection).
        swatches.forEachIndexed { i, c ->
            repeat(4) { dx -> source.setCell(i * 4 + dx, 3, 1, AsciiTileDescriptor('Û', c, Color.BLACK)) }
            repeat(4) { dx -> source.setCell(i * 4 + dx, 4, 1, AsciiTileDescriptor('Û', c, Color.BLACK)) }
        }

        // z=0: a recognizable floor/border so the whole visible area is covered.
        for (y in 0 until MAP_H) {
            for (x in 0 until MAP_W) {
                val edge = x == 0 || y == 0 || x == MAP_W - 1 || y == MAP_H - 1
                val glyph = if (edge) '#' else '.'
                val fg = if (edge) Color.GRAY else Color.LIGHT_GRAY
                source.setCell(x, y, 0, AsciiTileDescriptor(glyph, fg, Color.BLACK))
            }
        }

        // z=1: text and glyph coverage / legibility check.
        text(2, 7, "KOTILE RENDER HARNESS", Color.YELLOW)
        text(2, 9, "abcdefghijklmnopqrstuvwxyz", Color.WHITE)
        text(2, 10, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", Color.WHITE)
        text(2, 11, "0123456789  !@#\$%^&*()_+-=[]{}", Color.LIME)

        // z=1: foreground-brightness ramp '@' from 1.0 down to 0.0.
        text(2, 13, "fg brightness ramp 1.0 -> 0.0:", Color.WHITE)
        val ramp = 20
        repeat(ramp) { i ->
            val v = 1f - i.toFloat() / (ramp - 1)
            source.setCell(2 + i, 14, 1, AsciiTileDescriptor('@', Color(v, v, 0f, 1f), Color.BLACK))
        }
    }

    private fun text(x: Int, y: Int, s: String, fg: Color) {
        s.forEachIndexed { i, c ->
            val cx = x + i
            if (cx in 0 until MAP_W && y in 0 until MAP_H) {
                source.setCell(cx, y, 1, AsciiTileDescriptor(c, fg, Color.BLACK))
            }
        }
    }

    override fun render() {
        // Dark blue (not black) so the window extent and any "nothing drawn"
        // cells are distinguishable from genuinely black cells.
        Gdx.gl.glClearColor(0f, 0f, 0.25f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Same call krogue makes: render a windowed slice of a larger logical map.
        window.render(source, TileViewport(0, 0), elapsedMs = 0L)

        // Let the initial resize settle before grabbing the frame.
        if (++frame >= 2) {
            println(
                "HARNESS window=${window.widthInTiles}x${window.heightInTiles} tilePx=${window.tileWidthPx}x${window.tileHeightPx} " +
                    "logical=${Gdx.graphics.width}x${Gdx.graphics.height} backbuffer=${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}",
            )
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("HARNESS wrote $outPath")
            Gdx.app.exit()
        }
    }

    override fun resize(width: Int, height: Int) = window.resize(width, height)

    override fun dispose() = window.dispose()
}

fun main() {
    val outPath = System.getProperty("kotile.harness.out")
        ?: "${System.getProperty("user.dir")}/harness.png"

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile render harness")
            setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
            disableAudio(true)
        }
    Lwjgl3Application(RenderHarness(outPath), config)
}
