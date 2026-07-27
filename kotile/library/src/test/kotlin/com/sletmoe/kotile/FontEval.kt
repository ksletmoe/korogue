package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.GlyphFit
import java.io.File
import java.util.zip.Deflater
import kotlin.math.sin

/**
 * macOS-only font-evaluation harness for krogue-9x7.8: renders each candidate face through the real
 * [FreeTypeGlyphSource] at small Brogue-ish cell sizes with snapToPixelGrid on (the shipping crispness
 * path — TEXT band-scale), then (a) computes objective crispness/weight metrics over the downsampled
 * CP437 page and (b) dumps a per-font page PNG + a stitched scene strip so the letterforms can be
 * eyeballed. Not a shipped test — a comparison tool. Mirrors FreeTypeManualVerify's GL setup.
 *
 *   ./gradlew :kotile:library:fontEval -PfontsDir=/abs/path -PoutDir=/abs/path
 */
private const val COLS = 16
private const val ROWS = 16

private class Candidate(val label: String, val file: File)

private class FontEval(private val fontsDir: File, private val outDir: File) : ApplicationAdapter() {
    private var frame = 0

    // The Brogue-ish cell sizes where downscale crispness bites; TEXT fit + snap (band-scale path).
    private val sizes = listOf(16, 20)

    override fun render() {
        if (++frame < 2) return

        val candidates =
            listOf(
                "UbuntuMono-R (current default)" to "UbuntuMono-R.ttf",
                "UbuntuMono-Bold" to "UbuntuMono-Bold.ttf",
                "JetBrainsMono-Medium" to "JetBrainsMono-Medium.ttf",
                "JetBrainsMono-SemiBold" to "JetBrainsMono-SemiBold.ttf",
                "JetBrainsMono-Bold" to "JetBrainsMono-Bold.ttf",
                "IBMPlexMono-SemiBold" to "IBMPlexMono-SemiBold.ttf",
                "CascadiaMono-SemiBold" to "CascadiaMono-SemiBold.ttf",
                "CascadiaMono-Bold" to "CascadiaMono-Bold.ttf",
                "DejaVuSansMono-Bold" to "DejaVuSansMono-Bold.ttf",
            ).mapNotNull { (label, name) ->
                val f = File(fontsDir, name)
                if (f.exists()) {
                    Candidate(label, f)
                } else {
                    println("SKIP missing $name")
                    null
                }
            }

        println("\n=== krogue-9x7.8 font crispness eval (TEXT fit, snapToPixelGrid=on, ss=4) ===")
        println("metric key: weight=mean coverage of inked px (higher=bolder/darker);")
        println("            solid%=px>0.85 of inked (higher=crisper+bolder);")
        println("            grey%=px in [0.15,0.85] of inked (lower=crisper edges);")
        println("            blurRatio=Σsin(π·cov)/Σcov (lower=sharper per unit ink).\n")

        for (size in sizes) {
            println("--- cell ${size}x$size px ---")
            println(
                "%-34s %7s %7s %7s %9s %8s".format(
                    "face",
                    "weight",
                    "solid%",
                    "grey%",
                    "blurRatio",
                    "inkedPx",
                ),
            )
            for (c in candidates) {
                val src =
                    FreeTypeGlyphSource(
                        Gdx.files.absolute(c.file.absolutePath),
                        size,
                        size,
                        4,
                        fit = GlyphFit.TEXT,
                        snapToPixelGrid = true,
                    )
                val page = renderPage(src, size)
                val m = metrics(page)
                page.dispose()
                src.dispose()
                println(
                    "%-34s %7.3f %6.1f%% %6.1f%% %9.4f %8d".format(
                        c.label,
                        m.weight,
                        m.solidPct,
                        m.greyPct,
                        m.blurRatio,
                        m.inkedPx,
                    ),
                )
            }
            println()
        }

        // Eyeball dumps at the smaller size, where differences are starkest.
        val dumpSize = sizes.first()
        for (c in candidates) {
            val src =
                FreeTypeGlyphSource(
                    Gdx.files.absolute(c.file.absolutePath),
                    dumpSize,
                    dumpSize,
                    4,
                    fit = GlyphFit.TEXT,
                    snapToPixelGrid = true,
                )
            val page = renderPage(src, dumpSize)
            src.dispose()
            // upscale 4x nearest for viewing
            val big = upscale(page, 4)
            page.dispose()
            val safe = c.label.replace(Regex("[^A-Za-z0-9]+"), "_")
            val out = File(outDir, "eval-page-$safe.png")
            PixmapIO.writePNG(Gdx.files.absolute(out.absolutePath), big, Deflater.DEFAULT_COMPRESSION, false)
            big.dispose()
            println("wrote ${out.name}")
        }

        // Stitched scene strip: one dungeon/status line per font at a mid cell, 4x upscaled, for a
        // side-by-side look at real letterforms + box-drawing.
        renderSceneStrip(candidates, cell = 18)

        // A curated roguelike scene through the bundled DEFAULT face (classpath Cascadia Mono Bold),
        // exercising the coverage Ubuntu Mono lacked (arrows, hearts, box-drawing, shades).
        renderShowcase()

        println("\nFONTEVAL DONE")
        Gdx.app.exit()
    }

    private class ColoredPlaced(val col: Int, val row: Int, val slot: Int, val fg: Color)

    /** A framed dungeon + status screen rendered through the bundled default `Fonts.cascadiaMono`. */
    private fun renderShowcase() {
        // Rasterised at this cell px and written 1:1 (no viewer upscale) so the PNG is the engine's true
        // output pixels — a nearest upscale would magnify the downsample's edge AA and read as "soft".
        val cell = 40
        val cols = 34
        val rows = 19
        val src = com.sletmoe.kotile.display.ascii.Fonts.cascadiaMono(cell, cell, snapToPixelGrid = true)

        val wall = Color(0.62f, 0.60f, 0.68f, 1f)
        val floor = Color(0.34f, 0.34f, 0.40f, 1f)
        val hero = Color.WHITE
        val gold = Color(0.95f, 0.80f, 0.30f, 1f)
        val red = Color(0.90f, 0.30f, 0.30f, 1f)
        val green = Color(0.50f, 0.85f, 0.45f, 1f)
        val cyan = Color(0.45f, 0.85f, 0.95f, 1f)
        val magenta = Color(0.85f, 0.45f, 0.95f, 1f)
        val tan = Color(0.86f, 0.78f, 0.56f, 1f)

        val p = ArrayList<ColoredPlaced>()

        fun put(
            c: Int,
            r: Int,
            slot: Int,
            col: Color,
        ) {
            if (slot != 32) p += ColoredPlaced(c, r, slot, col)
        }

        fun text(
            c: Int,
            r: Int,
            s: String,
            col: Color,
        ) = s.forEachIndexed { i, ch -> put(c + i, r, ch.code, col) }

        text(1, 0, "korogue  ·  Cascadia Mono Bold", tan)

        // Framed map panel (cols 1..30, rows 2..14) with double-line box drawing.
        val left = 1
        val right = 30
        val top = 2
        val bot = 14
        put(left, top, 201, wall)
        put(right, top, 187, wall)
        put(left, bot, 200, wall)
        put(right, bot, 188, wall)
        for (c in left + 1 until right) {
            put(c, top, 205, wall)
            put(c, bot, 205, wall)
        }
        for (r in top + 1 until bot) {
            put(left, r, 186, wall)
            put(right, r, 186, wall)
        }
        // Floor fill.
        for (r in top + 1 until bot) for (c in left + 1 until right) put(c, r, '.'.code, floor)
        // Rubble/fog shades (light/medium/dark: slots 176/177/178).
        put(3, 4, 176, floor)
        put(4, 4, 177, floor)
        put(5, 4, 178, wall)
        put(26, 11, 176, floor)
        put(27, 11, 177, floor)
        // Inner wall studs.
        for (r in 6..9) put(15, r, '#'.code, wall)
        put(16, 6, '+'.code, gold) // a door
        // Actors + items.
        put(6, 5, '@'.code, hero)
        put(10, 7, 'k'.code, green) // kobold
        put(20, 6, 'r'.code, red) // rat
        put(24, 9, 'D'.code, red) // dragon
        put(12, 10, 'e'.code, cyan) // floating eye
        put(8, 8, '!'.code, magenta) // potion
        put(18, 9, '$'.code, gold) // gold
        put(22, 4, '='.code, gold) // ring
        put(27, 5, '?'.code, cyan) // scroll
        put(4, 12, '<'.code, tan)
        put(28, 12, '>'.code, tan) // stairs

        // Status block below the panel — hearts, arrows, ± the symbols the old face .notdef'd.
        text(1, 15, "Rodney the Rogue", hero)
        put(18, 15, 24, green)
        text(19, 15, "Level 3", green) // ↑ up-arrow
        // HP hearts (slot 3) + numbers.
        text(1, 16, "HP", red)
        for (i in 0 until 5) put(4 + i, 16, 3, red)
        text(10, 16, "18/18", red)
        text(17, 16, "Str 16", tan)
        text(25, 16, "Gold 240", gold)
        put(33, 16, '$'.code, gold)
        // Movement legend with arrows (←↑→↓ = slots 27 24 26 25) + a ~ water ripple (≈ = 247).
        text(1, 17, "Move", cyan)
        put(6, 17, 27, cyan)
        put(7, 17, 24, cyan)
        put(8, 17, 26, cyan)
        put(9, 17, 25, cyan)
        text(11, 17, "You see water", cyan)
        put(24, 17, 247, cyan)
        // Greek + math run placed by CP437 slot (glyph() is slot-indexed): Γ Σ Ω ∞ ≤ ≥ ½ ¼.
        val dim = Color(0.6f, 0.6f, 0.66f, 1f)
        intArrayOf(226, 228, 234, 32, 236, 243, 242, 32, 171, 172)
            .forEachIndexed { i, s -> put(1 + i, 18, s, dim) }
        text(13, 18, "complete CP437", dim)

        val pix = renderColored(src, cols, rows, cell, p, Color(0.06f, 0.06f, 0.09f, 1f))
        src.dispose()
        val out = File(outDir, "cascadia-showcase.png")
        PixmapIO.writePNG(Gdx.files.absolute(out.absolutePath), pix, Deflater.DEFAULT_COMPRESSION, false)
        pix.dispose()

        // Descender probe: a large TEXT-fit row of descender-heavy glyphs, each cell outlined, so any
        // baseline/descender clipping by the per-cell scissor is unmistakable.
        val big = 96
        val probeSrc = com.sletmoe.kotile.display.ascii.Fonts.cascadiaMono(big, big, snapToPixelGrid = true)
        val probe = "Rogue gjpqy Happy".map { it.code }
        val pp = ArrayList<ColoredPlaced>()
        probe.forEachIndexed { i, ch -> if (ch != 32) pp += ColoredPlaced(i, 0, ch, Color.WHITE) }
        val probePix = renderColored(probeSrc, probe.size, 1, big, pp, Color(0.10f, 0.10f, 0.13f, 1f))
        probeSrc.dispose()
        // Draw a 1px cell-floor guide line so the eye can see the baseline/descender vs the cell edge.
        probePix.setColor(0.9f, 0.3f, 0.3f, 1f)
        probePix.drawLine(0, big - 1, probePix.width - 1, big - 1)
        val probeOut = File(outDir, "cascadia-descenders.png")
        PixmapIO.writePNG(Gdx.files.absolute(probeOut.absolutePath), probePix, Deflater.DEFAULT_COMPRESSION, false)
        probePix.dispose()
        println("wrote ${probeOut.name}")
        println("wrote ${out.name} (${cols * cell}x${rows * cell}, native 1:1)")
    }

    /** Draws [placed] (per-glyph coloured CP437 slots) into a [cols]x[rows] grid of [cell]px on [bg]. */
    private fun renderColored(
        src: FreeTypeGlyphSource,
        cols: Int,
        rows: Int,
        cell: Int,
        placed: List<ColoredPlaced>,
        bg: Color,
    ): Pixmap {
        val w = cols * cell
        val h = rows * cell
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(bg.r, bg.g, bg.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, w.toFloat(), h.toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.begin()
        for (pl in placed) {
            val region = src.glyph(Char(pl.slot)) ?: continue
            batch.color = pl.fg
            batch.draw(
                region,
                (pl.col * cell).toFloat(),
                (h - (pl.row + 1) * cell).toFloat(),
                cell.toFloat(),
                cell.toFloat(),
            )
        }
        batch.end()
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        batch.dispose()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) flipped.drawPixmap(raw, 0, yy, 0, h - 1 - yy, w, 1)
        raw.dispose()
        fbo.dispose()
        return flipped
    }

    private class M(
        val weight: Float,
        val solidPct: Float,
        val greyPct: Float,
        val blurRatio: Float,
        val inkedPx: Int,
    )

    /** Coverage metrics over an upright white-on-black page pixmap (cov = red channel). */
    private fun metrics(page: Pixmap): M {
        var inkMass = 0.0
        var blur = 0.0
        var inked = 0
        var solid = 0
        var grey = 0
        val buf = page.pixels
        val n = page.width * page.height
        for (i in 0 until n) {
            val r = (buf.get(i * 4).toInt() and 0xFF) / 255.0 // RGBA8888, red first
            if (r <= 0.02) continue
            inked++
            inkMass += r
            blur += sin(Math.PI * r)
            if (r > 0.85) solid++
            if (r in 0.15..0.85) grey++
        }
        return M(
            weight = if (inked == 0) 0f else (inkMass / inked).toFloat(),
            solidPct = if (inked == 0) 0f else 100f * solid / inked,
            greyPct = if (inked == 0) 0f else 100f * grey / inked,
            blurRatio = if (inkMass == 0.0) 0f else (blur / inkMass).toFloat(),
            inkedPx = inked,
        )
    }

    /** Draws all 256 CP437 slots white-on-black 1:1 at [cell] px into an FBO; returns upright pixmap. */
    private fun renderPage(
        src: FreeTypeGlyphSource,
        cell: Int,
    ): Pixmap {
        val w = COLS * cell
        val h = ROWS * cell
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, w.toFloat(), h.toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.color = Color.WHITE
        batch.begin()
        for (slot in 0 until 256) {
            val region = src.glyph(Char(slot)) ?: continue
            val col = slot % COLS
            val row = slot / COLS
            batch.draw(region, (col * cell).toFloat(), (h - (row + 1) * cell).toFloat(), cell.toFloat(), cell.toFloat())
        }
        batch.end()
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        batch.dispose()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) flipped.drawPixmap(raw, 0, yy, 0, h - 1 - yy, w, 1)
        raw.dispose()
        fbo.dispose()
        return flipped
    }

    private fun upscale(
        src: Pixmap,
        f: Int,
    ): Pixmap {
        val out =
            Pixmap(src.width * f, src.height * f, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val p = src.getPixel(x, y)
                out.setColor(p)
                out.fillRectangle(x * f, y * f, f, f)
            }
        }
        return out
    }

    /** One line of representative text + box-drawing per font, stacked, 4x upscaled, into one PNG. */
    private fun renderSceneStrip(
        candidates: List<Candidate>,
        cell: Int,
    ) {
        // Built as CP437 SLOTS (glyph() indexes 0–255, not Unicode): ASCII maps to itself; the tail is
        // shades/blocks (176 177 178 219), a box frame (201 205 187 / 204 206 185), math (241 243 242 246),
        // and Greek (224 225 227 228) — the coverage that separates the candidates.
        val ascii = "The quick @ Dragon HP:18 ".map { it.code }
        val special =
            listOf(
                176, 177, 178, 219, 32, 201, 205, 205, 187, 32, 204, 206, 185, 32,
                241, 243, 242, 246, 32, 224, 225, 227, 228,
            )
        val sample = ascii + special
        val cols = sample.size
        val labelCols = 0
        val rowH = cell
        val stripW = (labelCols + cols) * cell
        val stripH = candidates.size * rowH

        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, stripW, stripH, false)
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, stripW.toFloat(), stripH.toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.begin()
        candidates.forEachIndexed { idx, c ->
            val src =
                FreeTypeGlyphSource(
                    Gdx.files.absolute(c.file.absolutePath),
                    cell,
                    cell,
                    4,
                    fit = GlyphFit.TEXT,
                    snapToPixelGrid = true,
                )
            val yTop = stripH - (idx + 1) * rowH
            batch.color = Color.WHITE
            sample.forEachIndexed { i, slot ->
                val region = src.glyph(Char(slot)) ?: return@forEachIndexed
                batch.draw(region, ((labelCols + i) * cell).toFloat(), yTop.toFloat(), cell.toFloat(), cell.toFloat())
            }
            batch.flush()
            src.dispose()
        }
        batch.end()
        val raw = Pixmap.createFromFrameBuffer(0, 0, stripW, stripH)
        fbo.end()
        batch.dispose()
        val flipped = Pixmap(stripW, stripH, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until stripH) flipped.drawPixmap(raw, 0, yy, 0, stripH - 1 - yy, stripW, 1)
        raw.dispose()
        fbo.dispose()
        val big = upscale(flipped, 3)
        flipped.dispose()
        val out = File(outDir, "eval-scene-strip.png")
        PixmapIO.writePNG(Gdx.files.absolute(out.absolutePath), big, Deflater.DEFAULT_COMPRESSION, false)
        big.dispose()
        println("wrote ${out.name} (rows top→bottom match the candidate order above)")
    }
}

fun main() {
    val fontsDir = File(System.getProperty("kotile.fonteval.fonts") ?: error("set -Dkotile.fonteval.fonts"))
    val outDir = File(System.getProperty("kotile.fonteval.out") ?: fontsDir.parentFile.absolutePath)
    outDir.mkdirs()
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(COLS * 20, ROWS * 20)
            setTitle("FontEval")
            setForegroundFPS(0)
        }
    Lwjgl3Application(FontEval(fontsDir, outDir), config)
}
