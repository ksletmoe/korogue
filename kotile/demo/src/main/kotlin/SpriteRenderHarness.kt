import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet
import java.io.File
import java.util.zip.Deflater

/**
 * Controlled visual harness for the **sprite** rendering path
 * ([SpriteTileRenderer] over an image [TileSheet]), the counterpart to
 * [RenderHarness] (which exercises the ASCII path).
 *
 * Why this exists: the macOS NPOT-atlas bug that garbled ASCII glyphs affected
 * *any* sliced sheet region, including image sprite sheets. This harness draws
 * the bundled CC0 "Vaarn" 8x8 sheet so a regression there is immediately
 * visible — if sprites render as speckled garbage, the NPOT fix in `TileSheet`
 * has regressed.
 *
 * It covers: untinted sheet tiles (crisp slicing check), per-tile hue tinting,
 * a tint-to-black brightness ramp, and an animated tile resolved at a fixed
 * wall-clock time.
 *
 * Run on macOS via the Gradle task (forks a JVM with -XstartOnFirstThread):
 *   ./gradlew :demo:spriteHarness -PoutFile=/tmp/sprite-harness.png
 *
 * The PNG is written with `flipY = true`, so it is right-side up.
 */
private const val WINDOW_W_PX = 800
private const val WINDOW_H_PX = 400

/** On-screen tile size. The Vaarn sheet's source tiles are 8x8; draw upscaled. */
private const val TILE_PX = 20

/** Fixed wall-clock time used to resolve animated tiles in the single captured frame. */
private const val FIXED_ELAPSED_MS = 250L

/**
 * Source tile size of the synthetic alpha-layering sheet. Larger than [TILE_PX]
 * so drawing it into a 20px cell is a *downscale* — exercising the mipmap path
 * and surfacing any alpha-edge halo/bleed (the krogue-ejd / krogue-4ni watch-out)
 * for eyeball verification.
 */
private const val LAYER_SRC_PX = 32

private class SpriteRenderHarness(private val outPath: String) : ApplicationAdapter() {
    private lateinit var sheet: TileSheet
    private lateinit var canvas: KotileCanvas
    private lateinit var sprites: SpriteTileRenderer
    private lateinit var layeredSheet: TileSheet
    private lateinit var layered: SpriteTileRenderer
    private var frame = 0

    override fun create() {
        sheet = TileSheet(Gdx.files.classpath("vaarn-8x8.png"), 8, 8)
        canvas = KotileCanvas(TILE_PX, TILE_PX)
        sprites = SpriteTileRenderer(canvas, sheet)
        // A synthetic two-tile sheet for the alpha-layering demonstration:
        // tile (0,0) is an opaque green "terrain" tile, tile (1,0) is a red
        // diamond on a transparent surround (a "creature" sprite). Shares the
        // one canvas via a second renderer.
        layeredSheet = TileSheet(Gdx.files.absolute(buildLayeredSheet()), LAYER_SRC_PX, LAYER_SRC_PX)
        layered = SpriteTileRenderer(canvas, layeredSheet)
        buildScene()
    }

    private fun buildScene() {
        // Rows 1-3: the sheet's art block (14x3 in the top-left), untinted. If
        // these render as crisp sprites, NPOT sub-region slicing is healthy; if
        // they're speckled garbage, the macOS NPOT fix has regressed.
        for (sy in 0..2) {
            for (sx in 0..13) {
                sprites.drawTile(1 + sx, 1 + sy, z = 0, staticTile = StaticTile(sx, sy))
            }
        }

        // Row 5: the same "stone" tile (0,2) with a per-tile hue tint — exercises
        // the multiply-tint color path on sprites.
        val hueLen = 36
        repeat(hueLen) { i ->
            val hue = Color(0f, 0f, 0f, 1f).fromHsv(i * 360f / hueLen, 1f, 1f)
            sprites.drawTile(1 + i, 5, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 2, tint = hue))
        }

        // Row 7: brightness ramp — same tile multiplied toward black, the sprite
        // analogue of the ASCII harness's fg ramp (mirrors krogue lighting).
        val rampLen = 20
        repeat(rampLen) { i ->
            val v = 1f - i.toFloat() / (rampLen - 1)
            sprites.drawTile(1 + i, 7, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 2, tint = Color(v, v, v, 1f)))
        }

        // Row 9: an animated tile cycling three art-block cells, resolved at
        // FIXED_ELAPSED_MS so the single captured frame proves frame selection
        // works (it won't be frame 0).
        val animated =
            AnimatedSpriteTile(
                frames =
                    listOf(
                        AnimationFrame(sheet.region(0, 0), durationMs = 100),
                        AnimationFrame(sheet.region(1, 0), durationMs = 100),
                        AnimationFrame(sheet.region(2, 0), durationMs = 100),
                    ),
                mode = PlaybackMode.LOOP,
            )
        for (x in 1..6) {
            sprites.drawTile(x, 9, z = 0, tile = animated)
        }

        // z=1 over z=0: layering check — a tinted tile on top of the art block.
        sprites.drawTile(3, 2, z = 1, staticTile = StaticTile(sheetX = 0, sheetY = 0, tint = Color.RED))

        // Row 12: alpha layering — a red diamond sprite (transparent surround)
        // on z=1 over a green terrain tile on z=0, both from the synthetic sheet.
        // The terrain must show through the sprite's transparent corners; drawn
        // downscaled (32px source -> 20px cell) so any mip halo is visible.
        for (x in 1..10) {
            layered.drawTile(x, 12, z = 0, staticTile = StaticTile(sheetX = 0, sheetY = 0)) // terrain
            layered.drawTile(x, 12, z = 1, staticTile = StaticTile(sheetX = 1, sheetY = 0)) // creature
        }
    }

    /**
     * Writes the synthetic alpha-layering sheet to a temp PNG and returns its
     * absolute path: a 2x1 grid of [LAYER_SRC_PX] tiles — an opaque green
     * terrain tile at (0,0) and a red diamond on a fully transparent surround
     * at (1,0).
     */
    private fun buildLayeredSheet(): String {
        val pixmap = Pixmap(LAYER_SRC_PX * 2, LAYER_SRC_PX, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        pixmap.setColor(Color.CLEAR)
        pixmap.fill()

        // tile (0,0): solid green terrain.
        pixmap.setColor(0.15f, 0.55f, 0.2f, 1f)
        pixmap.fillRectangle(0, 0, LAYER_SRC_PX, LAYER_SRC_PX)

        // tile (1,0): a filled red diamond centered on a transparent surround.
        pixmap.setColor(Color.RED)
        val half = LAYER_SRC_PX / 2
        for (dy in 0 until LAYER_SRC_PX) {
            val spread = half - Math.abs(dy - half + 1)
            if (spread > 0) pixmap.drawLine(LAYER_SRC_PX + half - spread, dy, LAYER_SRC_PX + half + spread, dy)
        }

        val file = File.createTempFile("kotile-layered-demo", ".png").apply { deleteOnExit() }
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
        pixmap.dispose()
        return file.absolutePath
    }

    override fun render() {
        // Dark blue (not black) so the window extent is visible.
        Gdx.gl.glClearColor(0f, 0f, 0.25f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        sprites.render(elapsedMs = FIXED_ELAPSED_MS)
        layered.render(elapsedMs = FIXED_ELAPSED_MS)

        if (++frame >= 2) {
            println(
                "SPRITE-HARNESS grid=${canvas.width}x${canvas.height} tilePx=$TILE_PX " +
                    "logical=${Gdx.graphics.width}x${Gdx.graphics.height} backbuffer=${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}",
            )
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("SPRITE-HARNESS wrote $outPath")
            Gdx.app.exit()
        }
    }

    override fun resize(width: Int, height: Int) {
        sprites.onResize(width, height) // rebuilds the internal tilemap
        layered.onResize(width, height) // both renderers track the canvas size
        buildScene() // ...so repopulate them
    }

    override fun dispose() {
        sprites.dispose()
        layered.dispose()
        canvas.dispose()
        sheet.dispose()
        layeredSheet.dispose()
    }
}

fun main() {
    val outPath = System.getProperty("kotile.harness.out")
        ?: "${System.getProperty("user.dir")}/sprite-harness.png"

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile sprite harness")
            setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
            disableAudio(true)
        }
    Lwjgl3Application(SpriteRenderHarness(outPath), config)
}
