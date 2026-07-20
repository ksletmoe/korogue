import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.IntegerScale
import java.util.zip.Deflater

/**
 * Visual proof for a **bundled font sheet**, rendered through the real
 * [Font] + [AsciiTileWindow] path (not a preview tool), so what the snapshot
 * shows is exactly what a consumer of [Fonts] gets. Renders any of the bundled
 * CP437 sizes (`-Pfont=8x8|9x16|10x10|12x12|16x16`) so a sheet can be eyeballed:
 * its glyphs, tinted by each cell's foreground color over both dark and light
 * backgrounds, expose keying artifacts (halos) if the sheet format is wrong.
 *
 * The scene: the full 256-glyph CP437 chart (16×16, code point 0 top-left),
 * a line of sample text, and a box-drawing frame — the three things a CP437
 * font has to get right (glyph shapes, layout order, seamless line pieces).
 *
 *   ./gradlew :kotile:demo:fontHarness                 # -> demo/build/font-harness.png
 *   ./gradlew :kotile:demo:fontHarness -Pfont=16x16    # any bundled size
 *   ./gradlew :kotile:demo:fontHarness -PoutFile=/tmp/f.png
 */
private class FontSampleHarness(
    private val outPath: String,
    private val fontName: String,
) : ApplicationAdapter() {
    private lateinit var font: Font
    private lateinit var window: AsciiTileWindow
    private var frame = 0

    private val amber = Color(0.92f, 0.86f, 0.62f, 1f)
    private val ink = Color(0.12f, 0.10f, 0.08f, 1f)
    private val paper = Color(0.82f, 0.80f, 0.72f, 1f)

    override fun create() {
        font = bundledFont(fontName)
        window =
            AsciiTileWindow.create {
                this.font = font
                widthInTiles = COLS
                heightInTiles = ROWS
                fitToWindow = false
                scalePolicy = IntegerScale
            }
        buildScene()
    }

    private fun buildScene() {
        window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLACK))

        // Full CP437 chart: all 256 glyphs, code point 0 at top-left, amber on dark.
        for (code in 0 until 256) {
            window.drawTile(code % 16, code / 16, StaticAsciiTile(code.toChar(), amber, Color.BLACK))
        }

        // Sample text on dark, then the same on a paper background to expose any
        // edge halos the alpha channel would otherwise hide on black.
        window.drawText(19, 1, "The quick brown fox", amber, Color.BLACK)
        window.drawText(19, 2, "jumps over 0123456789", amber, Color.BLACK)
        window.drawText(19, 4, "Dark ink on paper: $fontName", ink, paper)
        window.drawText(19, 5, "@ # $ % & * ( ) [ ] { }", ink, paper)

        // Box-drawing frame (single-line): proves the line pieces meet at corners.
        drawFrame(19, 8, 22, 6, amber)
        window.drawText(21, 10, "box-drawing frame", amber, Color.BLACK)
    }

    private fun drawFrame(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Color,
    ) {
        // This is a CP437 sheet: Font indexes glyphs by raw code point, so the
        // single-line box pieces are their CP437 codes (0xB3/0xBF/0xC0/0xC4/0xDA/
        // 0xD9), NOT the Unicode box-drawing chars (U+2500…, which are > 255 and
        // would render as nothing).
        val tl = 0xDA.toChar()
        val tr = 0xBF.toChar()
        val bl = 0xC0.toChar()
        val br = 0xD9.toChar()
        val horiz = 0xC4.toChar()
        val vert = 0xB3.toChar()
        val bg = Color.BLACK
        window.drawTile(x, y, StaticAsciiTile(tl, color, bg))
        window.drawTile(x + w - 1, y, StaticAsciiTile(tr, color, bg))
        window.drawTile(x, y + h - 1, StaticAsciiTile(bl, color, bg))
        window.drawTile(x + w - 1, y + h - 1, StaticAsciiTile(br, color, bg))
        for (i in 1 until w - 1) {
            window.drawTile(x + i, y, StaticAsciiTile(horiz, color, bg))
            window.drawTile(x + i, y + h - 1, StaticAsciiTile(horiz, color, bg))
        }
        for (i in 1 until h - 1) {
            window.drawTile(x, y + i, StaticAsciiTile(vert, color, bg))
            window.drawTile(x + w - 1, y + i, StaticAsciiTile(vert, color, bg))
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0.05f, 0.05f, 0.07f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        window.render()
        if (++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            try {
                PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            } finally {
                pixmap.dispose() // release native memory even if the PNG write throws
            }
            println("FONT HARNESS ($fontName) wrote $outPath")
            Gdx.app.exit()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = window.resize(width, height)

    override fun dispose() {
        window.dispose()
        font.dispose()
    }

    private companion object {
        const val COLS = 44
        const val ROWS = 16
    }
}

/** The bundled CP437 sizes this harness can render, ascending. */
private val BUNDLED_FONT_SIZES = listOf("8x8", "9x16", "10x10", "12x12", "16x16")

private fun bundledFont(name: String): Font =
    when (name) {
        "8x8" -> Fonts.cp437_8x8()
        "9x16" -> Fonts.cp437_9x16()
        "10x10" -> Fonts.cp437_10x10()
        "12x12" -> Fonts.cp437_12x12()
        "16x16" -> Fonts.cp437_16x16()
        else -> error("Unsupported font '$name'") // guarded by BUNDLED_FONT_SIZES in main()
    }

/** (cellWidthPx, cellHeightPx) parsed from a bundled size name like "9x16". */
private fun fontCellPx(name: String): Pair<Int, Int> {
    val (w, h) = name.split("x").map { it.toInt() }
    return w to h
}

fun main() {
    val outPath =
        System.getProperty("kotile.harness.out")
            ?: "${System.getProperty("user.dir")}/font-harness.png"
    val fontName = System.getProperty("kotile.harness.font") ?: "12x12"
    require(fontName in BUNDLED_FONT_SIZES) {
        "Unsupported -Pfont='$fontName'; expected one of: ${BUNDLED_FONT_SIZES.joinToString()}"
    }

    // Native cell px * an integer zoom, so IntegerScale upscales nearest-neighbour
    // with no letterboxing (44 cols x 16 rows of native cells, times the zoom).
    val (cellW, cellH) = fontCellPx(fontName)
    val zoom = 3
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile font sample: $fontName")
            setWindowedMode(44 * cellW * zoom, 16 * cellH * zoom)
            disableAudio(true)
        }
    Lwjgl3Application(FontSampleHarness(outPath, fontName), config)
}
