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
                "UbuntuMono-R (former default)" to "UbuntuMono-R.ttf",
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

        println("\nFONTEVAL DONE")
        Gdx.app.exit()
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

        // Build every source up front — each construction runs a full rasterise (its own FBO + batch), so
        // doing it inside the strip's bound FBO / active batch would nest GL passes and rely on rasterise's
        // state save/restore. Construct outside the pass, draw, then dispose.
        val sources =
            candidates.map {
                FreeTypeGlyphSource(
                    Gdx.files.absolute(it.file.absolutePath),
                    cell,
                    cell,
                    4,
                    fit = GlyphFit.TEXT,
                    snapToPixelGrid = true,
                )
            }

        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, stripW, stripH, false)
        val batch = SpriteBatch()
        // finally releases every hoisted source + the FBO/batch even if a draw/readback throws.
        val flipped =
            try {
                fbo.begin()
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                val cam =
                    OrthographicCamera().apply {
                        setToOrtho(false, stripW.toFloat(), stripH.toFloat())
                        update()
                    }
                batch.projectionMatrix = cam.combined
                batch.begin()
                sources.forEachIndexed { idx, src ->
                    val yTop = stripH - (idx + 1) * rowH
                    batch.color = Color.WHITE
                    sample.forEachIndexed { i, slot ->
                        val region = src.glyph(Char(slot)) ?: return@forEachIndexed
                        batch.draw(
                            region,
                            ((labelCols + i) * cell).toFloat(),
                            yTop.toFloat(),
                            cell.toFloat(),
                            cell.toFloat(),
                        )
                    }
                }
                batch.end()
                val raw = Pixmap.createFromFrameBuffer(0, 0, stripW, stripH)
                fbo.end()
                try {
                    Pixmap(stripW, stripH, Pixmap.Format.RGBA8888).apply {
                        blending = Pixmap.Blending.None
                        for (yy in 0 until stripH) drawPixmap(raw, 0, yy, 0, stripH - 1 - yy, stripW, 1)
                    }
                } finally {
                    raw.dispose()
                }
            } finally {
                batch.dispose()
                fbo.dispose()
                sources.forEach { it.dispose() }
            }
        val big = upscale(flipped, 3)
        flipped.dispose()
        val out = File(outDir, "eval-scene-strip.png")
        PixmapIO.writePNG(Gdx.files.absolute(out.absolutePath), big, Deflater.DEFAULT_COMPRESSION, false)
        big.dispose()
        println("wrote ${out.name} (rows top→bottom match the candidate order above)")
    }
}

fun main() {
    val fontsDir =
        File(System.getProperty("kotile.fonteval.fonts") ?: error("set -Dkotile.fonteval.fonts")).absoluteFile
    // A bare relative -PfontsDir has a null parentFile; fall back to the fonts dir itself.
    val outDir = File(System.getProperty("kotile.fonteval.out") ?: (fontsDir.parentFile ?: fontsDir).absolutePath)
    outDir.mkdirs()
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(COLS * 20, ROWS * 20)
            setTitle("FontEval")
            setForegroundFPS(0)
        }
    Lwjgl3Application(FontEval(fontsDir, outDir), config)
}
