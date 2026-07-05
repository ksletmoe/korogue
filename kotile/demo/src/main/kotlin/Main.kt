import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet
import java.util.zip.Deflater

private val BACKGROUND: Color = Color.valueOf("1d1f21ff")
private val LABEL: Color = Color.valueOf("9aa7b0ff")

class KotileDemo : ApplicationAdapter() {
    private lateinit var sheet: TileSheet
    private lateinit var spriteCanvas: KotileCanvas
    private lateinit var sprites: SpriteTileRenderer
    private lateinit var overlay: AsciiTileWindow
    private val snapshotPath: String? = System.getProperty("kotile.snapshot")
    private var frame = 0

    override fun create() {
        // The CC0 "Vaarn" sheet is 8x8 source tiles; draw them upscaled to 20px.
        sheet = TileSheet(Gdx.files.classpath("vaarn-8x8.png"), 8, 8)
        spriteCanvas = KotileCanvas(20, 20)
        sprites = SpriteTileRenderer(spriteCanvas, sheet)
        overlay = AsciiTileWindow.create {
            widthInTiles = 80
            heightInTiles = 30
        }

        drawSprites()
        drawOverlay()
    }

    /**
     * (Re)populates the sprite renderer. Both surfaces reflow on resize, which
     * rebuilds their grids and drops the previous content, so the scene is
     * redrawn from [resize] rather than only once in [create].
     */
    private fun drawSprites() {
        // The sheet's art occupies a 14x3 block in the top-left. Draw it as-is
        // (tint defaults to white = no color manipulation).
        for (sheetY in 0..2) {
            for (sheetX in 0..13) {
                putSprite(2 + sheetX, 2 + sheetY, StaticTile(sheetX, sheetY))
            }
        }

        // The same stone tile drawn repeatedly with a per-tile hue tint.
        val stripLength = 36
        repeat(stripLength) { i ->
            val hue = Color(0f, 0f, 0f, 1f).fromHsv(i * 360f / stripLength, 1f, 1f)
            putSprite(2 + i, 7, StaticTile(sheetX = 0, sheetY = 2, tint = hue))
        }
        // ...and once more untinted, for comparison.
        repeat(stripLength) { i ->
            putSprite(2 + i, 9, StaticTile(sheetX = 0, sheetY = 2))
        }
    }

    /**
     * Places a sprite only if it lands within the current (reflowed) grid.
     * Unlike [AsciiTileWindow.drawText], the sprite path throws on an
     * out-of-bounds cell, so shrinking the window past the showcase would
     * otherwise crash; clip it here instead.
     */
    private fun putSprite(x: Int, y: Int, tile: StaticTile) {
        if (x in 0 until sprites.windowWidth && y in 0 until sprites.windowHeight) {
            sprites.drawTile(x, y, z = 0, staticTile = tile)
        }
    }

    private fun drawOverlay() {
        overlay.drawText(2, 0, "kotile - image sprite sheet (CC0 Vaarn 8x8) + tinting", Color.LIME, Color.CLEAR)
        overlay.drawText(2, 2, "the sheet, untinted:", LABEL, Color.CLEAR)
        overlay.drawText(2, 13, "same tile, per-tile hue tint:", LABEL, Color.CLEAR)
        overlay.drawText(2, 17, "untinted:", LABEL, Color.CLEAR)
    }

    override fun render() {
        Gdx.gl.glClearColor(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        sprites.render()
        overlay.render()

        if (snapshotPath != null && ++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(snapshotPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            Gdx.app.exit()
        }
    }

    override fun resize(width: Int, height: Int) {
        // Resize BOTH surfaces (not just the overlay) and redraw, since each
        // reflows its grid and drops content that no longer fits.
        sprites.onResize(width, height)
        overlay.resize(width, height)
        drawSprites()
        drawOverlay()
    }

    override fun dispose() {
        overlay.dispose()
        spriteCanvas.dispose()
        sheet.dispose()
    }
}

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("kotile")
        setWindowedMode(800, 300)
    }
    Lwjgl3Application(KotileDemo(), config)
}
