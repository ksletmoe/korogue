import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileScaling
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import java.util.zip.Deflater

/**
 * Visual showcase for [TileSheetGlyphSource] (krogue-9x7.7, ADR-0043): a **high-resolution artist
 * tilesheet** resolved down to the on-screen cell, the route Brogue takes for its map art.
 *
 * There is no bundled artist sheet to ship, so the harness *draws* one — eight 128×128 masters of
 * hard-edged shapes (a brick wall, a fine lattice, rings, a gem, a tree, a creature, hatching, an arch),
 * in a mask version and a coloured version. Hard-edged is the point: every soft edge in the output was
 * produced by the downscale, not by the source art.
 *
 * The chart, top to bottom:
 *
 * 1. **One master, every cell size** — the same sheet at 8…48 px cells. Each row is a fresh
 *    `prepareForCellSize`, i.e. what a window resize does.
 * 2. **Per-cell tint** — one wall tile repeated through a hue ramp, which is what `TileInk.COVERAGE`
 *    buys: the sheet is a silhouette, the colour comes from the cell.
 * 3. **`TileInk.FULL_COLOR`** — the coloured sheet carried through instead, drawn untinted.
 * 4. **`snapToPixelGrid` off vs on**, magnified ×5 nearest-neighbour so the alignment is visible.
 * 5. **`STRETCH` vs `PRESERVE_ASPECT`** in a deliberately oblong 40×20 cell.
 *
 * Everything is drawn 1:1 into an offscreen buffer at the chart's exact size and captured from there, so
 * the PNG is true output pixels — no retina magnification dressing up the result.
 *
 *   ./gradlew :kotile:demo:tileSheetHarness                    # -> demo/build/tilesheet-harness.png
 *   ./gradlew :kotile:demo:tileSheetHarness -PoutFile=/tmp/t.png
 */
private class TileSheetHarness(
    private val outPath: String,
) : ApplicationAdapter() {
    private lateinit var maskSheet: FileHandle
    private lateinit var colorSheet: FileHandle
    private lateinit var labelFont: Font
    private lateinit var batch: SpriteBatch
    private val camera = OrthographicCamera()
    private var frame = 0

    override fun create() {
        maskSheet = writeDemoSheet(coloured = false)
        colorSheet = writeDemoSheet(coloured = true)
        labelFont = Fonts.cp437_8x8()
        batch = SpriteBatch()
    }

    override fun render() {
        // One warm-up frame so the window/GL state has settled before the capture, as the other harnesses do.
        if (++frame < 2) return

        val buffer = FrameBuffer(Pixmap.Format.RGBA8888, CANVAS_W, CANVAS_H, false)
        try {
            buffer.begin()
            Gdx.gl.glClearColor(BACKDROP.r, BACKDROP.g, BACKDROP.b, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            camera.setToOrtho(false, CANVAS_W.toFloat(), CANVAS_H.toFloat())
            camera.update()
            batch.projectionMatrix = camera.combined

            var y = MARGIN
            y = cellSizeRow(y)
            y = tintRow(y)
            y = fullColorRow(y)
            y = snapRow(y)
            aspectRow(y)

            val shot = Pixmap.createFromFrameBuffer(0, 0, CANVAS_W, CANVAS_H)
            buffer.end()
            try {
                // Offscreen pixels come back bottom-up; PixmapIO's own flip is the single correction
                // (flipping again here would mirror the chart, which is exactly what it did first time).
                PixmapIO.writePNG(Gdx.files.absolute(outPath), shot, Deflater.DEFAULT_COMPRESSION, true)
            } finally {
                shot.dispose()
            }
        } finally {
            buffer.dispose()
        }
        println("TILESHEET HARNESS wrote $outPath")
        Gdx.app.exit()
    }

    // ── The panels ─────────────────────────────────────────────────────────────────────────────────

    /** Panel 1: the same 128px masters at eight cell sizes — one `prepareForCellSize` per row. */
    private fun cellSizeRow(top: Int): Int {
        var y = label(MARGIN, top, "ONE 128px MASTER, RESOLVED AT EACH CELL SIZE (1:1 pixels)")
        val source = TileSheetGlyphSource(maskSheet, columns = TILES, rows = 1, 16, 16)
        try {
            for (cell in intArrayOf(8, 10, 12, 16, 20, 24, 32, 48)) {
                source.prepareForCellSize(cell, cell)
                val labelY = y + (cell - LABEL_PX) / 2
                text(MARGIN, labelY.coerceAtLeast(y), "${cell}px", DIM)
                var x = MARGIN + GUTTER
                for (slot in 0 until TILES) {
                    source.glyph(Char(slot))?.let { draw(it, x, y, cell, cell, INK) }
                    x += cell + 2
                }
                y += cell + ROW_GAP
            }
        } finally {
            source.dispose()
        }
        return y + SECTION_GAP
    }

    /** Panel 2: a coverage tile is a silhouette — the colour comes from the cell, as with any glyph. */
    private fun tintRow(top: Int): Int {
        var y = label(MARGIN, top, "TileInk.COVERAGE: ONE TILE, TINTED PER CELL")
        val source = TileSheetGlyphSource(maskSheet, columns = TILES, rows = 1, 24, 24)
        try {
            val wall = source.glyph(Char(WALL))
            val creature = source.glyph(Char(CREATURE))
            for (i in 0 until 16) {
                val hue = Color(0f, 0f, 0f, 1f).fromHsv(i * 360f / 16f, 0.55f, 1f)
                wall?.let { draw(it, MARGIN + i * 26, y, 24, 24, hue) }
                creature?.let { draw(it, MARGIN + i * 26, y + 26, 24, 24, hue) }
            }
        } finally {
            source.dispose()
        }
        y += 26 + 24
        return y + SECTION_GAP
    }

    /** Panel 3: the second ink — the sheet's own colour survives the downscale, and the tint multiplies. */
    private fun fullColorRow(top: Int): Int {
        var y = label(MARGIN, top, "TileInk.FULL_COLOR: THE SHEET'S OWN COLOUR (drawn untinted)")
        val source =
            TileSheetGlyphSource(colorSheet, columns = TILES, rows = 1, 32, 32, ink = TileInk.FULL_COLOR)
        try {
            for (slot in 0 until TILES) {
                source.glyph(Char(slot))?.let { draw(it, MARGIN + slot * 34, y, 32, 32, Color.WHITE) }
            }
        } finally {
            source.dispose()
        }
        y += 32
        return y + SECTION_GAP
    }

    /** Panel 4: pixel-grid alignment, magnified nearest-neighbour so the difference is legible. */
    private fun snapRow(top: Int): Int {
        val y = label(MARGIN, top, "snapToPixelGrid AT A 12px CELL, MAGNIFIED x5 (nearest)")
        val shown = intArrayOf(LATTICE, RINGS, GEM, CREATURE)
        var x = MARGIN
        for (snap in booleanArrayOf(false, true)) {
            val source =
                TileSheetGlyphSource(maskSheet, columns = TILES, rows = 1, 12, 12, snapToPixelGrid = snap)
            try {
                text(x, y, if (snap) "on" else "off", DIM)
                for (slot in shown) {
                    val region = source.glyph(Char(slot)) ?: continue
                    // The atlas is Linear-filtered for real use; magnifying it that way would blur exactly
                    // what this panel is about, so show the texels themselves.
                    region.texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                    draw(region, x, y + LABEL_PX + 2, 12 * ZOOM, 12 * ZOOM, INK)
                    x += 12 * ZOOM + 6
                }
            } finally {
                source.dispose()
            }
            x += 24
        }
        return y + LABEL_PX + 2 + 12 * ZOOM + SECTION_GAP
    }

    /** Panel 5: the coarse per-sheet answer to "the cell isn't square and my tile is". */
    private fun aspectRow(top: Int): Int {
        val y = label(MARGIN, top, "TileScaling IN A 40x20 CELL: STRETCH (left) vs PRESERVE_ASPECT (right)")
        var x = MARGIN
        for (scaling in TileScaling.values()) {
            val source =
                TileSheetGlyphSource(maskSheet, columns = TILES, rows = 1, 40, 20, scaling = scaling)
            try {
                for (slot in intArrayOf(WALL, RINGS, GEM, CREATURE)) {
                    source.glyph(Char(slot))?.let { draw(it, x, y, 40, 20, INK) }
                    x += 42
                }
            } finally {
                source.dispose()
            }
            x += 24
        }
        return y + 20
    }

    // ── Drawing helpers (y measured from the TOP of the chart) ─────────────────────────────────────

    private fun draw(
        region: TextureRegion,
        x: Int,
        top: Int,
        w: Int,
        h: Int,
        tint: Color,
    ) {
        batch.begin()
        batch.color = tint
        batch.draw(region, x.toFloat(), (CANVAS_H - top - h).toFloat(), w.toFloat(), h.toFloat())
        batch.color = Color.WHITE
        batch.end()
    }

    /** Draws [message] as 8px CP437 glyphs; returns the y the panel's content starts at. */
    private fun label(
        x: Int,
        top: Int,
        message: String,
    ): Int {
        text(x, top, message, HEADING)
        return top + LABEL_PX + 6
    }

    private fun text(
        x: Int,
        top: Int,
        message: String,
        tint: Color,
    ) {
        batch.begin()
        batch.color = tint
        message.forEachIndexed { i, char ->
            labelFont.glyph(char)?.let {
                batch.draw(
                    it,
                    (x + i * LABEL_PX).toFloat(),
                    (CANVAS_H - top - LABEL_PX).toFloat(),
                    LABEL_PX.toFloat(),
                    LABEL_PX.toFloat(),
                )
            }
        }
        batch.color = Color.WHITE
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
        labelFont.dispose()
    }

    private companion object {
        const val TILES = 8
        const val MASTER = 128
        const val CANVAS_W = 700
        const val CANVAS_H = 570
        const val MARGIN = 12
        const val GUTTER = 44
        const val ROW_GAP = 6
        const val SECTION_GAP = 18
        const val LABEL_PX = 8
        const val ZOOM = 5

        // Slot indices into the generated sheet.
        const val WALL = 0
        const val LATTICE = 1
        const val RINGS = 2
        const val GEM = 3
        const val CREATURE = 5

        val BACKDROP: Color = Color.valueOf("14161aff")
        val HEADING: Color = Color.valueOf("d7dde3ff")
        val DIM: Color = Color.valueOf("7f8a95ff")
        val INK: Color = Color.valueOf("e8e4d8ff")
    }
}

fun main() {
    val outPath =
        System.getProperty("kotile.harness.out")
            ?: "${System.getProperty("user.dir")}/tilesheet-harness.png"
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile tilesheet showcase")
            setWindowedMode(720, 720)
            disableAudio(true)
        }
    Lwjgl3Application(TileSheetHarness(outPath), config)
}
