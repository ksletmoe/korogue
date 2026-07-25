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
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.GlyphFit
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

// krogue-9x7.3 demo image geometry.
private const val SCENE_CELL = 40
private const val SCENE_COLS = 16
private const val SCENE_ROWS = 6
private const val STRIP_CELL = 20

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

        verifyTileFitAndBrightness(outPath)
        renderDemoComparison(outPath)

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

    /**
     * krogue-9x7.3 demo: renders the same little dungeon scene in TEXT vs TILE fit, and a strip of thin
     * glyphs with the brightness curve off vs on, stitched into one PNG (freetype-9x7.3-demo.png) so the
     * two knobs can be compared at a glance. Not a test — a picture for humans.
     */
    private fun renderDemoComparison(outPath: String) {
        val text = renderScene(GlyphFit.TEXT)
        val tile = renderScene(GlyphFit.TILE)
        // Thin-glyph strip at a small cell, where the peak-normalising curve actually bites.
        val brightOff = renderStrip(glyphBrightness = 1f)
        val brightOn = renderStrip(glyphBrightness = 3f)

        val gap = 6 * hidpi
        val w = maxOf(text.width, brightOff.width)
        val h = text.height + gap + tile.height + gap * 2 + brightOff.height + gap + brightOn.height
        val out =
            Pixmap(w, h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0.09f, 0.09f, 0.12f, 1f)
                fill()
            }
        var y = 0
        out.drawPixmap(text, 0, y)
        y += text.height + gap // TEXT scene
        out.drawPixmap(tile, 0, y)
        y += tile.height + gap * 2 // TILE scene
        out.drawPixmap(brightOff, 0, y)
        y += brightOff.height + gap // brightness off
        out.drawPixmap(brightOn, 0, y) // brightness on

        val path =
            outPath.replaceAfterLast('/', "freetype-9x7.3-demo.png").let {
                if (it == outPath) "$outPath.demo.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), out, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $path")
        listOf(text, tile, brightOff, brightOn, out).forEach { it.dispose() }
    }

    /** One placed glyph in a demo grid: CP437 [slot] at grid ([col], [row]) tinted [fg]. */
    private class Placed(val col: Int, val row: Int, val slot: Int, val fg: Color)

    /**
     * A tiny fixed dungeon scene at the given [fit]; used twice (TEXT vs TILE) for the demo image. The
     * source is built at the exact px it is drawn ([SCENE_CELL]) and blitted 1:1 (see [renderGrid]), so
     * the image shows the real glyph resolution rather than an upscale.
     */
    private fun renderScene(fit: GlyphFit): Pixmap {
        val source = Fonts.ubuntuMono(SCENE_CELL, SCENE_CELL, fit = fit)
        val wall = Color(0.62f, 0.6f, 0.7f, 1f)
        val floor = Color(0.28f, 0.28f, 0.34f, 1f)
        val hero = Color.WHITE
        val kobold = Color.LIME
        val dragon = Color.SCARLET
        val potion = Color(0.9f, 0.4f, 0.95f, 1f)
        val gold = Color.GOLD
        val placed = ArrayList<Placed>()
        // Box-drawing frame (slots: ╔201 ═205 ╗187 ║186 ╚200 ╝188), floor '.', @ hero, monsters, items.
        for (c in 0 until SCENE_COLS) placed +=
            Placed(
                c, 0,
                if (c == 0) {
                    201
                } else if (c == SCENE_COLS - 1) {
                    187
                } else {
                    205
                },
                wall,
            )
        for (c in 0 until SCENE_COLS) placed +=
            Placed(
                c, SCENE_ROWS - 1,
                if (c == 0) {
                    200
                } else if (c == SCENE_COLS - 1) {
                    188
                } else {
                    205
                },
                wall,
            )
        for (r in 1 until SCENE_ROWS - 1) {
            placed += Placed(0, r, 186, wall)
            placed += Placed(SCENE_COLS - 1, r, 186, wall)
            for (c in 1 until SCENE_COLS - 1) placed += Placed(c, r, '.'.code, floor)
        }
        // Overlay actors/items on the floor.
        placed += Placed(3, 2, '@'.code, hero)
        placed += Placed(11, 2, 'k'.code, kobold)
        placed += Placed(5, 3, '!'.code, potion)
        placed += Placed(13, 3, '$'.code, gold)
        placed += Placed(9, 4, 'D'.code, dragon)
        return renderGrid(source, SCENE_COLS, SCENE_ROWS, SCENE_CELL, placed)
    }

    /**
     * A one-row strip of thin/low-coverage glyphs (where the brightness curve is visible) at the given
     * [glyphBrightness]. Built at [STRIP_CELL] px and blitted 1:1, like [renderScene].
     */
    private fun renderStrip(glyphBrightness: Float): Pixmap {
        val source = Fonts.ubuntuMono(STRIP_CELL, STRIP_CELL, glyphBrightness = glyphBrightness)
        val slots = intArrayOf(46, 44, 58, 59, 39, 96, 45, 61, 179, 196, 176, 250, 249, 'i'.code, 'l'.code, 't'.code)
        val placed = slots.mapIndexed { i, slot -> Placed(i, 0, slot, Color.WHITE) }
        return renderGrid(source, slots.size, 1, STRIP_CELL, placed)
    }

    /**
     * Blits [placed] glyphs from [source] straight into a [cols]x[rows] grid of [cell]px cells (1:1, no
     * window, no HiDPI scaling — so the atlas px land on FBO px and the image is genuinely crisp). Each
     * glyph is drawn tinted, over a uniform dark background, matching the source's own y-up→readback
     * orientation (the same as `renderChart`). The [source] is disposed before returning.
     */
    private fun renderGrid(
        source: FreeTypeGlyphSource,
        cols: Int,
        rows: Int,
        cell: Int,
        placed: List<Placed>,
    ): Pixmap {
        val w = cols * cell
        val h = rows * cell
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fbo.begin()
        Gdx.gl.glClearColor(0.09f, 0.09f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        val batch = SpriteBatch()
        val cam =
            OrthographicCamera().apply {
                setToOrtho(false, w.toFloat(), h.toFloat())
                update()
            }
        batch.projectionMatrix = cam.combined
        batch.begin()
        for (p in placed) {
            val region = source.glyph(Char(p.slot)) ?: continue
            batch.color = p.fg
            batch.draw(
                region,
                (p.col * cell).toFloat(),
                (h - (p.row + 1) * cell).toFloat(),
                cell.toFloat(),
                cell.toFloat(),
            )
        }
        batch.end()
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) for (xx in 0 until w) flipped.drawPixel(xx, yy, raw.getPixel(xx, h - 1 - yy))
        raw.dispose()
        fbo.dispose()
        batch.dispose()
        source.dispose()
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

    /**
     * krogue-9x7.3: verifies the TILE fit and the per-glyph brightness curve on real pixels, and dumps a
     * TILE-mode CP437 page (freetype-tile.png) to eyeball placement/orientation. Prints stats so the
     * committed GL specs can be pinned to the observed discriminators.
     */
    private fun verifyTileFitAndBrightness(outPath: String) {
        val textSrc = Fonts.ubuntuMono(CELL, CELL) // TEXT, no brightness curve (the shipped default)
        val tileSrc = Fonts.ubuntuMono(CELL, CELL, fit = GlyphFit.TILE)
        val brightSrc = Fonts.ubuntuMono(CELL, CELL, glyphBrightness = 2f)
        val bright4Src = Fonts.ubuntuMono(CELL, CELL, glyphBrightness = 4f) // max cap — blank must still stay blank

        val textChart = renderChart(textSrc)
        val tileChart = renderChart(tileSrc)
        val brightChart = renderChart(brightSrc)
        val bright4Chart = renderChart(bright4Src)

        // TILE scale-fits each glyph, so a glyph fills more of its cell than TEXT's baseline layout.
        for (slot in listOf(65, 64, 47, 84)) { // 'A' '@' '/' 'T'
            val t = cellAvg(textChart, slot)
            val l = cellAvg(tileChart, slot)
            println("  FILL slot=$slot char='${Char(slot)}' TEXT=$t TILE=$l")
        }
        report("TILE fills 'A' more than TEXT", cellAvg(tileChart, 65) > cellAvg(textChart, 65), "")

        // Orientation: 'F' is top-heavy (two bars up top). Ink-centred + upright => its top half
        // out-inks its bottom half; a V-flipped atlas would invert that. (This is the discriminator the
        // committed GL spec uses.)
        val (fTop, fBot) = cellHalves(tileChart, 70) // 'F'
        println("  ORIENT TILE 'F' top=$fTop bot=$fBot (expect top>bot)")
        report("TILE 'F' upright (top-heavy, not flipped)", fTop > fBot + 0.03f, "top=$fTop bot=$fBot")

        // Brightness curve lifts thin glyphs whose peak coverage is < full. Print several; assert on the
        // ones that actually move (a stroke already at full ink is unchanged by peak-normalisation).
        for (slot in listOf(46, 58, 250, 196, 176, 65, 219)) { // . : · ─ ░ A █
            val b1 = cellAvg(textChart, slot)
            val b2 = cellAvg(brightChart, slot)
            println("  BRIGHT slot=$slot char='${Char(slot)}' off=$b1 on=$b2")
        }
        // The light shade (176) never reaches full ink, so the peak-normalising curve lifts it; the full
        // block (219) is already solid, so it is untouched. (The discriminators the committed GL spec uses.)
        report(
            "brightness lifts the light shade (176)",
            cellAvg(brightChart, 176) > cellAvg(textChart, 176) + 0.005f,
            "",
        )
        report("brightness leaves the full block (219) unchanged", cellAvg(brightChart, 219) > 0.9f, "")
        // ADR-0029: a blank/keyed-out glyph (space, 32) must stay transparent even at the max cap.
        report(
            "brightness keeps a blank glyph (32) transparent at cap 4",
            cellAvg(bright4Chart, 32) < 0.05f,
            "avg=${cellAvg(bright4Chart, 32)}",
        )

        val tilePath =
            outPath.replaceAfterLast(
                '/',
                "freetype-tile.png",
            ).let { if (it == outPath) "$outPath.tile.png" else it }
        PixmapIO.writePNG(Gdx.files.absolute(tilePath), tileChart, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $tilePath")

        textChart.dispose()
        tileChart.dispose()
        brightChart.dispose()
        bright4Chart.dispose()
        textSrc.dispose()
        tileSrc.dispose()
        brightSrc.dispose()
        bright4Src.dispose()
    }

    /** Average red over the top half vs the bottom half of CP437 [slot]'s cell interior (upright pixmap). */
    private fun cellHalves(
        pixels: Pixmap,
        slot: Int,
    ): Pair<Float, Float> {
        val cell = CELL * hidpi
        val x0 = (slot % COLS) * cell
        val y0 = (slot / COLS) * cell
        val lx = x0 + cell / 4
        val rx = x0 + 3 * cell / 4
        val top = pixels.averageColor(lx, y0 + cell / 8, rx, y0 + cell / 2).r
        val bot = pixels.averageColor(lx, y0 + cell / 2, rx, y0 + 7 * cell / 8).r
        return top to bot
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
