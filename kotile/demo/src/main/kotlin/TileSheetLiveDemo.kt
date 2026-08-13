import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import java.util.zip.Deflater

/**
 * The **live** counterpart to `TileSheetHarness`: a resizable window whose map is drawn from a
 * high-resolution artist tilesheet ([TileSheetGlyphSource], krogue-9x7.7 / ADR-0043), wired
 * `resolutionIndependent` so the cell **count** stays fixed and the cell **pixel size** tracks the
 * window. Every resize re-resolves the 128px masters at the new on-screen cell size and draws them 1:1 —
 * so dragging the window bigger reveals *more tile detail*, rather than magnifying the same pixels. That
 * is the property the still chart can only imply.
 *
 * Drag the window edges and watch the console: it prints the cell px each time the source re-rasterises.
 *
 * - **SPACE** — toggle `snapToPixelGrid` (rebuilds the source; most visible at small window sizes)
 * - **C** — toggle `TileInk.COVERAGE` (tinted silhouettes) against `TileInk.FULL_COLOR` (the sheet's
 *   own colour, drawn untinted)
 * - **ESC** — quit
 *
 *   ./gradlew :kotile:demo:tileSheetDemo
 *
 * The map is deliberately built from *full-bleed* terrain (brick walls, a lattice floor) next to
 * *floating* figures (a tree, a gem, a creature), because those two classes take different paths through
 * the downscale: the full-bleed ones are detected and sit out the shift search per axis so their edges
 * still meet, while the figures are free to be aligned to the pixel grid.
 */
private class TileSheetLiveDemo(
    private val snapshotPath: String?,
) : ApplicationAdapter() {
    private lateinit var maskSheet: FileHandle
    private lateinit var colorSheet: FileHandle
    private var window: AsciiTileWindow? = null
    private var snap = false
    private var ink = TileInk.COVERAGE
    private var lastCellPx = -1
    private var frame = 0

    override fun create() {
        maskSheet = writeDemoSheet(coloured = false)
        colorSheet = writeDemoSheet(coloured = true)
        rebuild()
        println("TILESHEET DEMO: SPACE = snapToPixelGrid, C = ink (COVERAGE/FULL_COLOR), ESC = quit")
    }

    /**
     * Builds (or rebuilds) the window and its glyph source for the current [snap]/[ink], then redraws the
     * scene. A rebuild is how the toggles work: both are constructor-time properties of the source, since
     * changing either invalidates every downscaled tile anyway.
     */
    private fun rebuild() {
        window?.dispose() // owns the glyph source, so this releases the old atlas too
        val source =
            TileSheetGlyphSource(
                if (ink == TileInk.COVERAGE) maskSheet else colorSheet,
                columns = TILES,
                rows = 1,
                cellWidthPx = 24,
                cellHeightPx = 24,
                ink = ink,
                snapToPixelGrid = snap,
            )
        window =
            AsciiTileWindow.create {
                glyphSource = source
                widthInTiles = COLS
                heightInTiles = ROWS
                fitToWindow = false
                resolutionIndependent = true
            }
        lastCellPx = -1
        // A window built mid-run starts at the source's construction cell size; push the real one through
        // the same resize path a drag would use, so the first frame is already at display resolution.
        window?.resize(Gdx.graphics.width, Gdx.graphics.height)
        drawScene()
    }

    /**
     * A small room: full-bleed brick walls and lattice floor, an arch, and a few figures. Under
     * [TileInk.COVERAGE] each cell supplies its own colour (the sheet is a silhouette); under
     * [TileInk.FULL_COLOR] everything is drawn white so the art's own colour comes through unmultiplied.
     */
    private fun drawScene() {
        val window = window ?: return
        val tint = { colour: Color -> if (ink == TileInk.COVERAGE) colour else Color.WHITE }

        window.fill(StaticAsciiTile(Char(FLOOR), tint(FLOOR_COLOUR), BACKDROP))
        for (x in 0 until COLS) {
            window.drawTile(x, 0, StaticAsciiTile(Char(WALL), tint(WALL_COLOUR), BACKDROP))
            window.drawTile(x, ROWS - 1, StaticAsciiTile(Char(WALL), tint(WALL_COLOUR), BACKDROP))
        }
        for (y in 0 until ROWS) {
            window.drawTile(0, y, StaticAsciiTile(Char(WALL), tint(WALL_COLOUR), BACKDROP))
            window.drawTile(COLS - 1, y, StaticAsciiTile(Char(WALL), tint(WALL_COLOUR), BACKDROP))
        }
        // An interior wall with a gap, so wall-to-wall seams are visible in two directions.
        for (y in 1 until ROWS - 5) {
            window.drawTile(COLS / 2, y, StaticAsciiTile(Char(WALL), tint(WALL_COLOUR), BACKDROP))
        }
        window.drawTile(COLS / 2, ROWS - 5, StaticAsciiTile(Char(ARCH), tint(ARCH_COLOUR), BACKDROP))

        window.drawTile(3, 3, StaticAsciiTile(Char(TREE), tint(TREE_COLOUR), BACKDROP))
        window.drawTile(6, 5, StaticAsciiTile(Char(TREE), tint(TREE_COLOUR), BACKDROP))
        window.drawTile(4, 7, StaticAsciiTile(Char(GEM), tint(GEM_COLOUR), BACKDROP))
        window.drawTile(9, 4, StaticAsciiTile(Char(RINGS), tint(RINGS_COLOUR), BACKDROP))
        window.drawTile(7, 8, StaticAsciiTile(Char(CREATURE), tint(CREATURE_COLOUR), BACKDROP))
        window.drawTile(COLS - 5, 3, StaticAsciiTile(Char(CREATURE), tint(CREATURE_COLOUR), BACKDROP))
        window.drawTile(COLS - 7, 7, StaticAsciiTile(Char(GEM), tint(GEM_COLOUR), BACKDROP))
        window.drawTile(COLS - 4, 8, StaticAsciiTile(Char(RINGS), tint(RINGS_COLOUR), BACKDROP))
        window.drawTile(COLS - 8, 5, StaticAsciiTile(Char(TREE), tint(TREE_COLOUR), BACKDROP))
    }

    override fun render() {
        handleInput()
        Gdx.gl.glClearColor(BACKDROP.r, BACKDROP.g, BACKDROP.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        window?.render()

        // Report each re-rasterisation, which is the thing worth watching while dragging the window.
        val cell = window?.tileWidthPx ?: -1
        if (cell != lastCellPx) {
            lastCellPx = cell
            println("TILESHEET DEMO: cell ${cell}px (128px master resolved down) snap=$snap ink=$ink")
        }

        // Non-interactive smoke path: prove the live wiring renders, then leave.
        snapshotPath?.let { path ->
            if (++frame >= 3) {
                val shot =
                    Pixmap.createFromFrameBuffer(
                        0,
                        0,
                        Gdx.graphics.backBufferWidth,
                        Gdx.graphics.backBufferHeight,
                    )
                try {
                    PixmapIO.writePNG(Gdx.files.absolute(path), shot, Deflater.DEFAULT_COMPRESSION, true)
                } finally {
                    shot.dispose()
                }
                println("TILESHEET DEMO wrote $path")
                Gdx.app.exit()
            }
        }
    }

    private fun handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) Gdx.app.exit()
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            snap = !snap
            rebuild()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.C)) {
            ink = if (ink == TileInk.COVERAGE) TileInk.FULL_COLOR else TileInk.COVERAGE
            rebuild()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        // The resolution-independent path: fixed cell count, cell px from the window, source
        // re-rasterised to match. The scene survives (the grid keeps its dimensions), so no redraw.
        window?.resize(width, height)
    }

    override fun dispose() {
        window?.dispose()
    }

    private companion object {
        const val COLS = 28
        const val ROWS = 16
        const val TILES = 8

        // Sheet slots (see DemoTileSheet.kt).
        const val WALL = 0
        const val FLOOR = 1
        const val RINGS = 2
        const val GEM = 3
        const val TREE = 4
        const val CREATURE = 5
        const val ARCH = 7

        val BACKDROP: Color = Color.valueOf("11131aff")
        val WALL_COLOUR: Color = Color.valueOf("8d7f6cff")
        val FLOOR_COLOUR: Color = Color.valueOf("1f242cff")
        val ARCH_COLOUR: Color = Color.valueOf("b9a37aff")
        val TREE_COLOUR: Color = Color.valueOf("5f9a55ff")
        val GEM_COLOUR: Color = Color.valueOf("58d6cdff")
        val RINGS_COLOUR: Color = Color.valueOf("d0a834ff")
        val CREATURE_COLOUR: Color = Color.valueOf("c95b62ff")
    }
}

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile: hi-res tilesheet, live (SPACE = snap, C = ink, ESC = quit)")
            setWindowedMode(896, 512)
            setResizable(true)
            disableAudio(true)
        }
    Lwjgl3Application(TileSheetLiveDemo(System.getProperty("kotile.demo.snapshot")), config)
}
