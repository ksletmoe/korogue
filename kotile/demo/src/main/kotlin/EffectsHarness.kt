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
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.Effect
import com.sletmoe.kotile.rendering.EffectsLayer
import com.sletmoe.kotile.rendering.LayerStack
import java.util.zip.Deflater

/**
 * Visual harness for the free-layer effects path (krogue-tk9, ADR-0018): a
 * grid drawn as a [com.sletmoe.kotile.rendering.Layer] with an [EffectsLayer]
 * of "bolts" composited on top at **sub-tile** pixel positions — deliberately
 * placed between cell rows/columns so the free (non-grid-snapped) placement is
 * obvious against the character grid beneath.
 *
 * The bolts carry a velocity and are advanced by a fixed number of update steps
 * before the snapshot, so the same code path a game would drive per frame
 * (stack.update -> stack.render) produces the still.
 *
 *   ./gradlew :kotile:demo:effectsHarness                     # -> demo/build/effects-harness.png
 *   ./gradlew :kotile:demo:effectsHarness -PoutFile=/tmp/e.png
 */
private const val WINDOW_W_PX = 600
private const val WINDOW_H_PX = 300
private const val COLS = 60
private const val ROWS = 30

/** Advance steps and per-step delta applied before the snapshot (60 fps-ish). */
private const val PRE_ADVANCE_STEPS = 40
private const val STEP_MS = 16L

private class EffectsHarness(private val outPath: String) : ApplicationAdapter() {
    private lateinit var font: Font
    private lateinit var canvas: KotileCanvas
    private lateinit var window: AsciiTileWindow
    private lateinit var boltTexture: Texture
    private val effects = EffectsLayer()
    private lateinit var stack: LayerStack
    private var frame = 0

    override fun create() {
        font = Fonts.cp437_10x10()
        canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)
        window =
            AsciiTileWindow.createWithCanvas(canvas, font) {
                widthInTiles = COLS
                heightInTiles = ROWS
                fitToWindow = false // fixed grid: effect pixel positions stay put across resize
            }
        boltTexture = solidTexture(Color.WHITE)

        buildGrid()

        stack = LayerStack(canvas)
        stack.add(window.asLayer()) // grid, below
        stack.add(effects) // free effects, on top

        spawnBolts()
        repeat(PRE_ADVANCE_STEPS) { stack.update(STEP_MS) }
    }

    private fun buildGrid() {
        // A dim floor of '.' so the bolts have a grid to sit "between".
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                window.drawTile(x, y, StaticAsciiTile('.', Color(0.25f, 0.28f, 0.32f, 1f), Color.CLEAR))
            }
        }
        window.drawText(1, 0, "free effects layer: bolts at sub-tile positions", Color.LIME, Color.CLEAR)
    }

    /**
     * Spawns a diagonal volley of colored bolts. Each is a half-tile square
     * placed on a half-cell offset (so it straddles four cells) and moving up
     * and to the right; [PRE_ADVANCE_STEPS] of motion carry them mid-screen.
     */
    private fun spawnBolts() {
        // Native tile size: the fixed 600x300 window over a 60x30 10px grid is
        // exactly 1x, so native == scaled content pixels here. A harness that
        // scaled the grid would instead read canvas.layout.tileWidthPx.
        val tile = font.charWidthPx.toFloat()
        val size = tile * 0.6f
        val colors = listOf(Color.RED, Color.ORANGE, Color.YELLOW, Color.CYAN, Color.SKY, Color.MAGENTA)
        colors.forEachIndexed { i, color ->
            // Start near the bottom-left on a half-cell offset (sub-tile).
            val cellX = 3 + i
            val cellY = 24 - i
            effects.spawn(
                Effect(
                    pxX = (cellX + 0.5f) * tile - size / 2f,
                    pxY = (cellY + 0.5f) * tile - size / 2f,
                    w = size,
                    h = size,
                    region = TextureRegion(boltTexture),
                    tint = color,
                    velXPerMs = 0.18f,
                    velYPerMs = -0.09f,
                ),
            )
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stack.render()

        if (++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("EFFECTS HARNESS wrote $outPath (active effects=${effects.activeCount})")
            Gdx.app.exit()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = window.resize(width, height)

    override fun dispose() {
        window.dispose() // shared canvas + font not owned by the window
        canvas.dispose()
        font.dispose()
        boltTexture.dispose()
    }

    private fun solidTexture(color: Color): Texture {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(color)
        pixmap.fill()
        return Texture(pixmap).also { pixmap.dispose() }
    }
}

fun main() {
    val outPath =
        System.getProperty("kotile.harness.out")
            ?: "${System.getProperty("user.dir")}/effects-harness.png"

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile effects harness")
            setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
            disableAudio(true)
        }
    Lwjgl3Application(EffectsHarness(outPath), config)
}
