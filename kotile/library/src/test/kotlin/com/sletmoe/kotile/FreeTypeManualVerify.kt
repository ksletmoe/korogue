package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.FitScale
import com.sletmoe.kotile.rendering.FractionalScaleMode
import java.util.zip.Deflater

/**
 * macOS-only manual verification for the tier-3 freetype glyph source
 * (krogue-9x7.2): the Kotest GL specs can't run on macOS (GLFW needs the first
 * thread), so this boots a real GL context on the main thread and (a) dumps a PNG
 * of the full CP437 page rendered through [FreeTypeGlyphSource] so glyph shape and
 * cell placement can be eyeballed, and (b) reads pixels back to assert the atlas
 * is populated sensibly (full block opaque, space empty, letters partial) and that
 * [FreeTypeGlyphSource.prepareForCellSize] re-rasterises. Lives in the test
 * sourceset for the internal test helpers and the freetype native.
 *
 *   ./gradlew :kotile:library:freetypeVerify            # -> build/freetype-verify.png
 */
private const val CELL = 24
private const val COLS = 16
private const val ROWS = 16
private val WIN_W = CELL * COLS
private val WIN_H = CELL * ROWS

private class FreeTypeManualVerify(private val outPath: String) : ApplicationAdapter() {
    private var frame = 0
    private var failures = 0
    private var hidpi = 1

    override fun render() {
        if (++frame < 2) return
        hidpi = (Gdx.graphics.backBufferWidth / Gdx.graphics.width).coerceAtLeast(1)
        println("FTVERIFY logical=${Gdx.graphics.width}x${Gdx.graphics.height} hidpi=$hidpi")

        val source = Fonts.ubuntuMono(CELL, CELL)
        report(
            "prepareForCellSize starts at requested size",
            source.charWidthPx == CELL && source.charHeightPx == CELL,
            "w=${source.charWidthPx} h=${source.charHeightPx}",
        )
        source.prepareForCellSize(32, 32)
        report(
            "prepareForCellSize re-rasterises to new size",
            source.charWidthPx == 32 && source.charHeightPx == 32,
            "w=${source.charWidthPx} h=${source.charHeightPx}",
        )
        source.prepareForCellSize(CELL, CELL)

        val pixels = renderChart(source)

        // Readback: sample cell interiors (avoid the very edges). Full block (219) should be near-white;
        // space (32) empty (black bg shows); 'A' (65) somewhere in between.
        val block = cellAvg(pixels, 219)
        val space = cellAvg(pixels, 32)
        val letterA = cellAvg(pixels, 65)
        report("full-block glyph (219) is opaque/bright", block > 0.8f, "avg=$block")
        report("space glyph (32) is empty/dark", space < 0.1f, "avg=$space")
        report("letter 'A' (65) has partial coverage", letterA in 0.05f..0.8f, "avg=$letterA")

        // Mirror the committed odd-supersample-pass orientation guard on real pixels.
        fullBlockOrientationCheck()

        PixmapIO.writePNG(Gdx.files.absolute(outPath), pixels, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $outPath")

        // Composition proof (the "draw big, then supersample" model): a 48px freetype MASTER rendered
        // through a fixed grid downscaled by FitScale with SUPERSAMPLE, so the master is gamma-
        // downsampled to a smaller on-screen cell. Dumped to eyeball smoothness vs a blocky upscale.
        val compPath =
            outPath.replaceAfterLast('/', "freetype-composition.png").let {
                if (it == outPath) "$outPath.comp.png" else it
            }
        val comp = renderComposition()
        PixmapIO.writePNG(Gdx.files.absolute(compPath), comp, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $compPath")
        comp.dispose()

        // Resolution-independent path (rasterise AT the on-screen cell size, re-raster on resize) at the
        // default supersample — the smooth, Brogue-comparable route.
        val riPath = compPath.replaceAfterLast('/', "freetype-resindep.png")
        val ri = renderResolutionIndependent(4)
        PixmapIO.writePNG(Gdx.files.absolute(riPath), ri, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $riPath")
        ri.dispose()

        println(if (failures == 0) "FTVERIFY: ALL PASSED" else "FTVERIFY: $failures FAILED")

        pixels.dispose()
        source.dispose()
        Gdx.app.exit()
    }

    /**
     * Renders a text/glyph banner from a 48px freetype MASTER through a fixed grid that FitScale
     * downscales, with SUPERSAMPLE, so the master is gamma-downsampled to the (smaller) on-screen cell
     * — tier 3 composed with tier 2. This is what "rasterise big, then supersample" produces.
     */
    private fun renderComposition(): Pixmap {
        val w = WIN_W * hidpi
        val h = WIN_H * hidpi
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // 48px master, 20x10 grid -> 960x480 native, FitScale into the 384px window -> ~0.4x (downscale).
        val master = Fonts.ubuntuMono(48, 48)
        val window =
            AsciiTileWindow.create {
                glyphSource = master
                widthInTiles = 20
                heightInTiles = 10
                fitToWindow = false
                scalePolicy = FitScale
                fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE
            }
        val amber = Color(0.95f, 0.82f, 0.45f, 1f)
        window.drawText(1, 1, "korogue kotile", amber)
        window.drawText(1, 3, "The quick brown fox", Color.WHITE)
        window.drawText(1, 4, "jumps over @ dragon.", Color.WHITE)
        window.drawText(1, 6, "HP:12 Str:16 ± ♥♦♣♠", Color.LIME)
        // A box-drawing frame corner sample.
        window.drawText(1, 8, "╔══╗ ░▒▓█", Color.CYAN)
        window.render()
        report("composition used supersample", window.backingCanvas.supersampledLastPass, "supersampledLastPass")

        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) for (xx in 0 until w) flipped.drawPixel(xx, yy, raw.getPixel(xx, h - 1 - yy))
        raw.dispose()
        fbo.dispose()
        window.dispose() // owns `master`
        return flipped
    }

    /**
     * Renders the same banner through a resolution-independent window: the freetype source is
     * re-rasterised at the on-screen cell size (via resize) and drawn 1:1 — no master downsample, no
     * fractional scale. This should be the crispest of the three.
     */
    private fun renderResolutionIndependent(supersample: Int): Pixmap {
        val w = WIN_W * hidpi
        val h = WIN_H * hidpi
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val window =
            AsciiTileWindow.create {
                glyphSource =
                    FreeTypeGlyphSource(Gdx.files.classpath("fonts/UbuntuMono-R.ttf"), 16, 16, supersample)
                widthInTiles = 20
                heightInTiles = 10
                resolutionIndependent = true
            }
        // Drive a resize so it re-rasterises to the on-screen cell px (cellPx = min(384/20, 384/10) = 19).
        window.resize(WIN_W, WIN_H)
        println("  resIndep ss=$supersample native cell now ${window.tileWidthPx}x${window.tileHeightPx}px")
        // Opaque dark background on every cell (the default REPLACE blit writes transparent for empty
        // cells, which a PNG viewer shows as white — a real opaque game fills its cells).
        window.fill(StaticAsciiTile(' ', Color.WHITE, Color(0.05f, 0.05f, 0.07f, 1f)))
        val amber = Color(0.95f, 0.82f, 0.45f, 1f)
        window.drawText(1, 1, "korogue kotile", amber)
        window.drawText(1, 3, "The quick brown fox", Color.WHITE)
        window.drawText(1, 4, "jumps over @ dragon.", Color.WHITE)
        window.drawText(1, 6, "HP:12 Str:16", Color.LIME)
        window.render()

        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) for (xx in 0 until w) flipped.drawPixel(xx, yy, raw.getPixel(xx, h - 1 - yy))
        raw.dispose()
        fbo.dispose()
        window.dispose()
        return flipped
    }

    /** Renders the 16×16 CP437 chart (slot per cell) through an AsciiTileWindow into a capture FBO. */
    private fun renderChart(source: FreeTypeGlyphSource): Pixmap {
        val w = WIN_W * hidpi
        val h = WIN_H * hidpi
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val window =
            AsciiTileWindow.create {
                glyphSource = source
                widthInTiles = COLS
                heightInTiles = ROWS
                fitToWindow = false
            }
        for (slot in 0 until 256) {
            window.drawTile(slot % COLS, slot / COLS, StaticAsciiTile(Char(slot), Color.WHITE, Color.BLACK))
        }
        window.render()
        // create() makes the window own `source`; we keep `source` alive for the readback and dispose
        // it ourselves at the end, so deliberately do NOT dispose the window here (that would take the
        // shared source with it). Its canvas/caches leak until process exit — fine for a one-shot harness.
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) for (xx in 0 until w) flipped.drawPixel(xx, yy, raw.getPixel(xx, h - 1 - yy))
        raw.dispose()
        fbo.dispose()
        return flipped
    }

    /**
     * Renders the full CP437 page with a supersample=8 source (3 halving passes, odd) and checks the
     * full block (slot 219) is still opaque in its own cell — a vertically flipped atlas would put a
     * sparse '+' there. Uses the chart geometry (matches this harness window) rather than a 1x1 grid,
     * which would mis-scale here since the harness window isn't resized per-check.
     */
    private fun fullBlockOrientationCheck() {
        val ss8 = FreeTypeGlyphSource(Gdx.files.classpath("fonts/UbuntuMono-R.ttf"), CELL, CELL, 8)
        val chart = renderChart(ss8)
        val block = cellAvg(chart, 219)
        chart.dispose()
        ss8.dispose()
        report("full block (ss=8) atlas upright, not flipped", block > 0.9f, "cellAvg(219)=$block")
    }

    /** Average brightness (red channel) over the interior of CP437 [slot]'s cell in the chart pixmap. */
    private fun cellAvg(
        pixels: Pixmap,
        slot: Int,
    ): Float {
        val cell = CELL * hidpi
        val x0 = (slot % COLS) * cell + cell / 4
        val y0 = (slot / COLS) * cell + cell / 4
        return pixels.averageColor(x0, y0, x0 + cell / 2, y0 + cell / 2).r
    }

    private fun report(
        name: String,
        ok: Boolean,
        detail: String,
    ) {
        if (!ok) failures++
        println("  [${if (ok) "PASS" else "FAIL"}] $name — $detail")
    }
}

fun main() {
    val outPath =
        System.getProperty("kotile.ftverify.out")
            ?: "${System.getProperty("user.dir")}/freetype-verify.png"
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile freetype verify")
            setWindowedMode(WIN_W, WIN_H)
            disableAudio(true)
            setInitialVisible(false)
        }
    Lwjgl3Application(FreeTypeManualVerify(outPath), config)
}
