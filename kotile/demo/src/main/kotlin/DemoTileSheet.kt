import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import java.io.File

// The synthetic "artist" tilesheet the [TileSheetGlyphSource][com.sletmoe.kotile.display.ascii.TileSheetGlyphSource]
// demos draw from (krogue-9x7.7, ADR-0043). kotile bundles no hand-drawn sheet — and shipping one would
// mean shipping someone's art — so the demos generate their own: eight 128x128 masters, far above any
// cell size, which is the whole premise of the source.
//
// Shared by the still chart (`TileSheetHarness`) and the live window (`TileSheetLiveDemo`) so both show
// the *same* art, and a change to a shape shows up in both.
//
// Tile slots: 0 brick wall, 1 lattice, 2 rings, 3 gem, 4 tree, 5 creature, 6 hatching, 7 arch.

/**
 * Writes a 8×1 sheet of 128px masters to a temp PNG and returns its handle. Shapes are drawn with
 * **hard edges** (Pixmap primitives do no antialiasing), so any smoothness in the chart is the
 * downscale's doing. [coloured] swaps the white ink for a per-tile palette, for the `FULL_COLOR` panel.
 */
internal fun writeDemoSheet(coloured: Boolean): FileHandle {
    val master = 128
    val tiles = 8
    val pixmap =
        Pixmap(master * tiles, master, Pixmap.Format.RGBA8888).apply {
            blending = Pixmap.Blending.None
            setColor(0f, 0f, 0f, 0f)
            fill()
        }
    val ink = { slot: Int -> if (coloured) PALETTE[slot] else Color.WHITE }
    val clear = Color(0f, 0f, 0f, 0f)

    fun rect(
        slot: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Color,
    ) = pixmap.run {
        setColor(color)
        fillRectangle(slot * master + x, y, w, h)
    }

    // 0: brick wall -- full-bleed, so it must tile edge to edge (and sits out the shift search).
    rect(0, 0, 0, master, master, ink(0))
    for (row in 0 until 8) {
        val offset = if (row % 2 == 0) 0 else 8
        rect(0, 0, row * 16 + 14, master, 2, clear) // mortar course
        for (brick in 0..8) {
            rect(0, (brick * 32 + offset - 2).coerceIn(0, master - 1), row * 16, 2, 14, clear)
        }
    }

    // 1: a fine lattice -- 3px strokes on a 16px pitch, i.e. detail far below what the cell can hold.
    for (i in 0..8) {
        rect(1, (i * 16).coerceAtMost(master - 3), 0, 3, master, ink(1))
        rect(1, 0, (i * 16).coerceAtMost(master - 3), master, 3, ink(1))
    }

    // 2: concentric rings -- the classic resampling stress case, floating in a margin. Painted
    // outside-in, alternating ink and clear, so each pass carves the next ring out of the last.
    intArrayOf(56, 46, 34, 24, 14, 6).forEachIndexed { i, radius ->
        pixmap.setColor(if (i % 2 == 0) ink(2) else clear)
        pixmap.fillCircle(2 * master + 64, 64, radius)
    }

    // 3: a gem -- straight diagonals, which alias badly without an area filter.
    pixmap.setColor(ink(3))
    pixmap.fillTriangle(3 * master + 64, 12, 3 * master + 116, 56, 3 * master + 64, 116)
    pixmap.fillTriangle(3 * master + 64, 12, 3 * master + 12, 56, 3 * master + 64, 116)
    pixmap.setColor(clear)
    pixmap.fillTriangle(3 * master + 64, 34, 3 * master + 92, 56, 3 * master + 64, 70)

    // 4: a tree.
    rect(4, 56, 72, 16, 48, ink(4))
    pixmap.setColor(ink(4))
    pixmap.fillCircle(4 * master + 64, 52, 40)
    pixmap.fillCircle(4 * master + 36, 72, 24)
    pixmap.fillCircle(4 * master + 92, 72, 24)

    // 5: a creature -- a body with holes for eyes, so the mask has interior detail.
    pixmap.setColor(ink(5))
    pixmap.fillCircle(5 * master + 64, 72, 42)
    pixmap.fillTriangle(5 * master + 28, 44, 5 * master + 40, 4, 5 * master + 60, 40)
    pixmap.fillTriangle(5 * master + 100, 44, 5 * master + 88, 4, 5 * master + 68, 40)
    pixmap.setColor(clear)
    pixmap.fillCircle(5 * master + 48, 64, 9)
    pixmap.fillCircle(5 * master + 80, 64, 9)
    pixmap.fillRectangle(5 * master + 48, 92, 32, 6)

    // 6: diagonal hatching -- full-bleed, and every stroke crosses the pixel grid at an angle. Drawn into
    // its own tile-sized pixmap and blitted, because a diagonal that starts off the tile's left edge runs
    // into the NEIGHBOURING tile in a shared sheet -- Pixmap has no clip rect. (It did exactly that at
    // first: the creature next door came out wearing stripes.)
    val hatch =
        Pixmap(master, master, Pixmap.Format.RGBA8888).apply {
            blending = Pixmap.Blending.None
            setColor(0f, 0f, 0f, 0f)
            fill()
            setColor(ink(6))
            for (i in -8..16) {
                for (t in 0 until 4) {
                    drawLine(i * 16 + t, 0, i * 16 + master + t, master)
                }
            }
        }
    try {
        pixmap.drawPixmap(hatch, 6 * master, 0)
    } finally {
        hatch.dispose()
    }

    // 7: an arch -- bleeds off three edges, so only its top axis may be snapped.
    rect(7, 0, 0, master, master, ink(7))
    pixmap.setColor(clear)
    pixmap.fillCircle(7 * master + 64, 68, 40)
    pixmap.fillRectangle(7 * master + 24, 68, 80, 60)

    val file = Gdx.files.absolute(File.createTempFile("kotile-demo-sheet", ".png").absolutePath)
    try {
        PixmapIO.writePNG(file, pixmap)
    } finally {
        pixmap.dispose()
    }
    return file
}

/** Per-tile ink for the coloured (FULL_COLOR) sheet: brick, lattice, rings, gem, tree, creature, hatch, arch. */
private val PALETTE =
    listOf(
        "8c6a52ff",
        "5f7f9aff",
        "c9a227ff",
        "4fd0c8ff",
        "4e8f4aff",
        "b4535aff",
        "6f6a8dff",
        "7a7266ff",
    ).map { Color.valueOf(it) }
