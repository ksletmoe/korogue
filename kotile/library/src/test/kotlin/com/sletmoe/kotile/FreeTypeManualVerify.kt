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
import kotlin.math.abs
import kotlin.system.measureNanoTime

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

    /** The textFill korogue-rogue's Brogue mode uses; the wall-seam check verifies the sizes it draws at. */
    private val rogueTextFill = 1.28f

    /** The step krogue-4ari currently produces at a 48px cell. A worsening result must fail, not print. */
    private val knownBottomSeamStep = 1
    private var hidpi = 1

    override fun render() {
        if (++frame < 2) return
        hidpi = (Gdx.graphics.backBufferWidth / Gdx.graphics.width).coerceAtLeast(1)
        println("FTVERIFY logical=${Gdx.graphics.width}x${Gdx.graphics.height} hidpi=$hidpi")

        val source = Fonts.cascadiaMono(CELL, CELL)
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
        verifyBandScaleCommittedShape()
        verifyTopClipCommittedShape()
        verifyCellFillSeamsCommittedShape()
        verifyAtlasGutterCommittedShape()
        verifyStrokeSnapCommittedShape()
        verifyShiftCacheCommittedShape()
        verifyBoxAlignedGlyphs()
        verifyWallRunContinuity()
        renderDemoComparison(outPath)
        renderBandScaleComparison(outPath)
        renderBrogueComparison(outPath)
        renderBrogueShowcase(outPath)
        renderBrogueMatch(outPath)
        renderShowcase(outPath)
        renderDescenderProbe(outPath)

        println(if (failures == 0) "FTVERIFY: ALL PASSED" else "FTVERIFY: $failures FAILED")

        pixels.dispose()
        // `source` was consumed (and disposed) by its renderChart window above.
        // Fail the task, don't merely report: printing "N FAILED" and exiting 0 let `freetypeVerify`
        // finish BUILD SUCCESSFUL with checks red, which is how a regression would slip through a run
        // that looked green. Thrown before exit so the JavaExec sees a non-zero result.
        try {
            check(failures == 0) { "FTVERIFY: $failures FAILED" }
        } finally {
            Gdx.app.exit()
        }
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
        val master = Fonts.cascadiaMono(48, 48)
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
                    FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), 16, 16, supersample)
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
        val source = Fonts.cascadiaMono(SCENE_CELL, SCENE_CELL, fit = fit)
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
        return renderGrid(source, SCENE_COLS, SCENE_ROWS, SCENE_CELL, SCENE_CELL, placed)
    }

    /**
     * A one-row strip of thin/low-coverage glyphs (where the brightness curve is visible) at the given
     * [glyphBrightness]. Built at [STRIP_CELL] px and blitted 1:1, like [renderScene].
     */
    private fun renderStrip(glyphBrightness: Float): Pixmap {
        val source = Fonts.cascadiaMono(STRIP_CELL, STRIP_CELL, glyphBrightness = glyphBrightness)
        val slots = intArrayOf(46, 44, 58, 59, 39, 96, 45, 61, 179, 196, 176, 250, 249, 'i'.code, 'l'.code, 't'.code)
        val placed = slots.mapIndexed { i, slot -> Placed(i, 0, slot, Color.WHITE) }
        return renderGrid(source, slots.size, 1, STRIP_CELL, STRIP_CELL, placed)
    }

    /**
     * Renders a sample at **Brogue's exact tile pipeline** for a side-by-side crispness comparison
     * (BrogueCE `src/platform/tiles.c`). Brogue's per-cell master is a fixed `TILE_WIDTH`×`TILE_HEIGHT` =
     * 128×232 px image (aspect 16:29), downsampled to the on-screen cell (`outputWidth/COLS` ×
     * `outputHeight/ROWS`, COLS=100 ROWS=34) in **linear colour space at gamma 2.0** (`dst += value*value`).
     *
     * kotile's master is `supersample × cell`, so the two cases below reproduce Brogue's 128×232 master
     * exactly, at two on-screen cell sizes, with kotile's own gamma-correct linear-light downsample:
     * - `16×29 @ ss8` → 128×232 master (Brogue at a ~16px screen cell — window ≈ 1600 wide)
     * - `32×58 @ ss4` → 128×232 master (2× on-screen)
     *
     * Glyph *shapes* differ (Brogue ships its own tileset; this is Cascadia Mono), so what's comparable is
     * the antialiasing/downsample crispness, not the letterforms.
     */
    private fun renderBrogueSample(
        cellW: Int,
        cellH: Int,
        supersample: Int,
        snap: Boolean,
    ): Pixmap {
        val source =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                cellW,
                cellH,
                supersample,
                snapToPixelGrid = snap,
            )
        val placed = ArrayList<Placed>()

        fun text(
            row: Int,
            s: String,
            color: Color,
        ) = s.forEachIndexed { i, ch -> placed += Placed(i, row, ch.code, color) }
        text(0, "The quick brown fox", Color.WHITE)
        text(1, "jumped over @ dragon", Color.WHITE)
        text(2, "0123456789 +-=*/!?%", Color.WHITE)
        // Box-drawing frame + shade/block elements (explicit CP437 slots; ' ' = 32 gaps).
        val box = intArrayOf(201, 205, 205, 187, 32, 186, 32, 200, 205, 205, 188, 32, 176, 177, 178, 219)
        box.forEachIndexed { i, slot -> placed += Placed(i, 3, slot, Color.CYAN) }
        // Pure black field to match Brogue's #000 (a grey field lowers contrast and reads dimmer).
        return renderGrid(source, 20, 4, cellW, cellH, placed, bg = Color.BLACK)
    }

    /**
     * Renders a few Brogue sidebar strings at Brogue's **measured on-screen cell** (31×53 device px in
     * the reference screenshot), TEXT-fit + shift-search, white on black — so it can be stitched 1:1
     * against a device-pixel crop of a real Brogue window (no viewer-zoom mismatch). Writes
     * freetype-brogue-match.png; each source line occupies one 53px row.
     */
    private fun renderBrogueMatch(outPath: String) {
        val cw = 31
        val ch = 53
        val src =
            FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), cw, ch, 8, snapToPixelGrid = true)
        val lines = listOf("Str: 12  Armor: 3", "Stealth range: 14", "A scroll entitled")
        val placed = ArrayList<Placed>()
        lines.forEachIndexed {
                r,
                s,
            ->
            s.forEachIndexed { i, c -> if (c != ' ') placed += Placed(i, r, c.code, Color.WHITE) }
        }
        val pix = renderGrid(src, lines.maxOf { it.length }, lines.size, cw, ch, placed, bg = Color.BLACK)
        val path =
            outPath.replaceAfterLast('/', "freetype-brogue-match.png").let {
                if (it == outPath) "$outPath.match.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), pix, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $path")
        pix.dispose()
    }

    /**
     * A Brogue-like showcase at kotile's crispest settings (Brogue's 32×58 cell, ss4 → 128×232 master,
     * shift-search on) for a direct side-by-side with a real Brogue screenshot: a TEXT-fit message/status
     * block over a TILE-fit dungeon map, on pure black. Writes freetype-brogue-showcase.png.
     */
    private fun renderBrogueShowcase(outPath: String) {
        val cw = 32
        val ch = 58
        val wall = Color(0.55f, 0.55f, 0.62f, 1f)
        val floor = Color(0.30f, 0.30f, 0.36f, 1f)
        val door = Color(0.62f, 0.44f, 0.24f, 1f)
        val tan = Color(0.85f, 0.78f, 0.55f, 1f)

        // TEXT-fit block: messages + a status line (snap on).
        val textSrc =
            FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), cw, ch, 4, snapToPixelGrid = true)
        val textPlaced = ArrayList<Placed>()

        fun line(
            row: Int,
            s: String,
            color: Color,
        ) = s.forEachIndexed { i, c -> if (c != ' ') textPlaced += Placed(i, row, c.code, color) }
        line(0, "Welcome, adventurer, to the Dungeons", tan)
        line(1, "of Doom! The quick brown fox jumps.", Color.WHITE)
        line(2, "@  HP:18/18   Str:16   Depth: 3", Color.LIME)
        val textPanel = renderGrid(textSrc, 37, 3, cw, ch, textPlaced, bg = Color.BLACK)

        // TILE-fit dungeon map (snap on): single glyph per cell, ink-centred.
        val tileSrc =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                cw,
                ch,
                4,
                fit = GlyphFit.TILE,
                snapToPixelGrid = true,
            )
        val mapRows =
            listOf(
                "######################",
                "#........#..........+.",
                "#..@..k..#....!.....r.#",
                "#........+..........=.#",
                "#...r....#.....e......#",
                "######################",
            )
        val colorOf = { c: Char ->
            when (c) {
                '@' -> Color.WHITE
                'k' -> Color.LIME
                'r' -> Color(0.82f, 0.52f, 0.24f, 1f)
                'e' -> Color.CYAN
                '!' -> Color(0.9f, 0.4f, 0.95f, 1f)
                '=' -> Color.GOLD
                '+' -> door
                '#' -> wall
                '.' -> floor
                else -> Color.WHITE
            }
        }
        val mapPlaced = ArrayList<Placed>()
        mapRows.forEachIndexed {
                r,
                s,
            ->
            s.forEachIndexed { c, ch2 -> if (ch2 != ' ') mapPlaced += Placed(c, r, ch2.code, colorOf(ch2)) }
        }
        val mapPanel = renderGrid(tileSrc, 22, mapRows.size, cw, ch, mapPlaced, bg = Color.BLACK)

        val gap = 20
        val w = maxOf(textPanel.width, mapPanel.width)
        val h = textPanel.height + gap + mapPanel.height
        val out =
            Pixmap(w, h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(Color.BLACK)
                fill()
            }
        out.drawPixmap(textPanel, 0, 0)
        out.drawPixmap(mapPanel, 0, textPanel.height + gap)
        val path =
            outPath.replaceAfterLast('/', "freetype-brogue-showcase.png").let {
                if (it == outPath) "$outPath.show.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), out, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $path")
        textPanel.dispose()
        mapPanel.dispose()
        out.dispose()
    }

    /**
     * Stitches four Brogue-parameter panels (16×29 @ ss8 and 32×58 @ ss4 — both Brogue's exact 128×232
     * master — each rendered without and with output-pixel snapping) into freetype-brogue.png, on pure
     * black, for comparing crispness against a real Brogue screenshot.
     */
    private fun renderBrogueComparison(outPath: String) {
        val panels =
            listOf(
                renderBrogueSample(16, 29, 8, snap = false),
                renderBrogueSample(16, 29, 8, snap = true),
                renderBrogueSample(32, 58, 4, snap = false),
                renderBrogueSample(32, 58, 4, snap = true),
            )
        val gap = 12
        val w = panels.maxOf { it.width }
        val h = panels.sumOf { it.height } + gap * (panels.size - 1)
        val out =
            Pixmap(w, h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(Color.BLACK)
                fill()
            }
        var y = 0
        for (p in panels) {
            out.drawPixmap(p, 0, y)
            y += p.height + gap
        }
        // Objective crispness: Brogue's blur metric Σ sin(π·coverage) — smaller = fewer grey-edged pixels.
        // The shift search minimises this per glyph, so snapped should be < unsnapped.
        val b16Plain = panelBlur(panels[0])
        val b16Snap = panelBlur(panels[1])
        val b32Plain = panelBlur(panels[2])
        val b32Snap = panelBlur(panels[3])
        println(
            "  BLUR 16px  unsnapped=%.0f  snapped=%.0f  (%.1f%% less)".format(
                b16Plain,
                b16Snap,
                100 * (b16Plain - b16Snap) / b16Plain,
            ),
        )
        println(
            "  BLUR 32px  unsnapped=%.0f  snapped=%.0f  (%.1f%% less)".format(
                b32Plain,
                b32Snap,
                100 * (b32Plain - b32Snap) / b32Plain,
            ),
        )
        val path =
            outPath.replaceAfterLast(
                '/',
                "freetype-brogue.png",
            ).let { if (it == outPath) "$outPath.brogue.png" else it }
        PixmapIO.writePNG(Gdx.files.absolute(path), out, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $path")
        panels.forEach { it.dispose() }
        out.dispose()
    }

    /**
     * krogue-9x7.5 (+ krogue-ux6): reproduces the committed GL spec's EXACT shape on macOS (which the
     * Kotest GL suite cannot run here) — a direct `renderGrid` SpriteBatch region blit of a 16×16,
     * supersample 8 lowercase row (no AsciiTileWindow/compositor, matching the spec's `renderLowercaseRow`),
     * band-scaled vs translation-only — and asserts what the spec asserts: the band-scaled atlas differs
     * substantially from the translation-only one (the warp; 0 if band scaling were disabled) AND the
     * band-scaled baseline is tight (≤ 1 output row). Per CLAUDE.md: mirror the committed test's
     * geometry/GL state, not just the logic, so a green harness predicts a green CI. (The prior strict
     * `bandSpread < transSpread` signal was a ≤1px knife-edge that ux6's em-shrink collapsed — see the
     * spec's note.)
     */
    private fun verifyBandScaleCommittedShape() {
        // Reuse the committed spec's own shape literals (not copies) so this mirror can't drift from it.
        val letters = BAND_LETTERS
        val flatBottom = BAND_FLAT_BOTTOM
        val cell = BAND_CELL

        fun row(disableBand: Boolean): Pixmap {
            val source =
                FreeTypeGlyphSource(
                    Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                    cell,
                    cell,
                    8,
                    snapToPixelGrid = true,
                    disableBandScale = disableBand,
                )
            val placed = letters.mapIndexed { i, c -> Placed(i, 0, c.code, Color.WHITE) }
            return renderGrid(source, letters.length, 1, cell, cell, placed, bg = Color.BLACK) // disposes source
        }

        val bandRow = row(disableBand = false)
        val transRow = row(disableBand = true)
        var diff = 0
        var inked = 0
        for (y in 0 until bandRow.height) {
            for (x in 0 until bandRow.width) {
                val a1 = bandRow.getPixel(x, y) ushr 24 and 0xFF
                val a2 = transRow.getPixel(x, y) ushr 24 and 0xFF
                if (a1 > ALPHA_EPS || a2 > ALPHA_EPS) inked++
                if (abs(a1 - a2) > ALPHA_EPS) diff++
            }
        }
        val bandSpread = baselineSpread(bandRow, letters, row = 0, cw = cell, ch = cell, consider = flatBottom)
        bandRow.dispose()
        transRow.dispose()
        val pct = 100 * diff / inked.coerceAtLeast(1)
        println(
            "  BANDSCALE committed-shape 16x16  band-vs-translation diff=$diff/$inked ($pct%)  bandSpread=$bandSpread",
        )
        report(
            "band scaling differs from translation-only (regression signal; 0 if disabled)",
            inked > 0 && diff > inked / 10,
            "diff=$diff inked=$inked",
        )
        report("band spread <= 1 (baseline essentially flat)", bandSpread in 0..1, "band=$bandSpread")
    }

    /**
     * krogue-9x7.5: lowercase-focused A/B of the vertical band scaling. Renders a lowercase-heavy line
     * three ways at Brogue's 32×58 cell — snap off (uniform), translation-only shift search (band scaling
     * bypassed via the `disableBandScale` seam), and the shipped band-scaled snap — and prints the blur
     * metric of each, plus stitches them (freetype-bandscale.png) so the x-height/baseline snapping is
     * eyeballable. Band-scaled should have the least blur; the point is that it beats translation-only on
     * lowercase, which the ADR-0037 shift search could not.
     */
    private fun renderBandScaleComparison(outPath: String) {
        val stitched = ArrayList<Pixmap>()
        renderBandScaleAt(16, 29, ss = 8, into = stitched)
        renderBandScaleAt(32, 58, ss = 4, into = stitched)
        val gap = 20
        val w = stitched.maxOf { it.width }
        val h = stitched.sumOf { it.height } + gap * (stitched.size - 1)
        val out =
            Pixmap(w, h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(Color.BLACK)
                fill()
            }
        var y = 0
        for (p in stitched) {
            out.drawPixmap(p, 0, y)
            y += p.height + gap
        }
        val path =
            outPath.replaceAfterLast('/', "freetype-bandscale.png").let {
                if (it == outPath) "$outPath.band.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), out, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $path")
        (stitched + out).forEach { it.dispose() }
    }

    /**
     * One size's block of the band-scale comparison ([cw]×[ch] cell, [ss] supersample): renders a
     * lowercase-heavy sample three ways (snap off, translation-only, band-scaled), prints blur + baseline
     * spread, and appends the three panels to [into] for stitching.
     */
    private fun renderBandScaleAt(
        cw: Int,
        ch: Int,
        ss: Int,
        into: MutableList<Pixmap>,
    ) {
        fun panel(
            snap: Boolean,
            disableBand: Boolean,
        ): Pixmap {
            val source =
                FreeTypeGlyphSource(
                    Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                    cw,
                    ch,
                    ss,
                    snapToPixelGrid = snap,
                    disableBandScale = disableBand,
                )
            val lines = listOf("the quick brown fox", "jumps over lazy dog", "excellence adequacy")
            val placed = ArrayList<Placed>()
            lines.forEachIndexed { r, s ->
                s.forEachIndexed { i, c -> if (c != ' ') placed += Placed(i, r, c.code, Color.WHITE) }
            }
            return renderGrid(source, lines.maxOf { it.length }, lines.size, cw, ch, placed, bg = Color.BLACK)
        }
        val off = panel(snap = false, disableBand = false)
        val trans = panel(snap = true, disableBand = true) // translation-only (ADR-0037)
        val band = panel(snap = true, disableBand = false) // band-scaled (krogue-9x7.5, shipped)
        val bOff = panelBlur(off)
        val bTrans = panelBlur(trans)
        val bBand = panelBlur(band)
        println("  BANDSCALE lowercase ${cw}x$ch  off=%.0f  translation=%.0f  band=%.0f".format(bOff, bTrans, bBand))
        println(
            "    band vs off: %.1f%% less   band vs translation-only: %.1f%% less".format(
                100 * (bOff - bBand) / bOff,
                100 * (bTrans - bBand) / bTrans,
            ),
        )
        // The real band-scaling win: a CONSISTENT baseline across letters. Translation-only picks each
        // glyph's own vertical offset, so flat-bottomed lowercase can sit on slightly different rows (an
        // uneven line); band scaling pins them all to one snapped baseline row. Measure the spread of the
        // bottom inked row over flat-bottomed x-height letters in row 0 ("the quick brown fox").
        val flatBottom = "theuickbrownfox" // letters from row 0 without descenders/dots (skip q, i, ' ')
        val transSpread = baselineSpread(trans, "the quick brown fox", row = 0, cw = cw, ch = ch, consider = flatBottom)
        val bandSpread = baselineSpread(band, "the quick brown fox", row = 0, cw = cw, ch = ch, consider = flatBottom)
        println("  BANDSCALE baseline-row spread ${cw}x$ch  translation=$transSpread  band=$bandSpread (band ~0)")
        val gap = 12
        val w = maxOf(off.width, trans.width, band.width)
        val h = off.height + trans.height + band.height + gap * 2
        val block =
            Pixmap(w, h, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(Color.BLACK)
                fill()
            }
        var y = 0
        for (p in listOf(off, trans, band)) {
            block.drawPixmap(p, 0, y)
            y += p.height + gap
        }
        into += block
        listOf(off, trans, band).forEach { it.dispose() }
    }

    /**
     * Spread (max − min) of the bottom inked row across the cells of [line]'s [row] whose character is in
     * [consider], in the panel [pix] (a [cols]×… grid of [cw]×[ch] cells, char i at column i). A tight
     * spread means every letter shares a baseline; a loose one means the line sits unevenly.
     */
    private fun baselineSpread(
        pix: Pixmap,
        line: String,
        row: Int,
        cw: Int,
        ch: Int,
        consider: String,
    ): Int {
        val bottoms = ArrayList<Int>()
        line.forEachIndexed { i, c ->
            if (c !in consider) return@forEachIndexed
            val x0 = i * cw
            val y0 = row * ch
            var bottom = -1
            for (y in 0 until ch) {
                var inked = false
                for (x in x0 + 2 until x0 + cw - 2) {
                    if ((pix.getPixel(x, y0 + y) ushr 24 and 0xFF) > 96) {
                        inked = true
                        break
                    }
                }
                if (inked) bottom = y
            }
            if (bottom >= 0) bottoms += bottom
        }
        // Fail fast (as the committed spec's sibling does) rather than return a -1 sentinel that would
        // slip past the spread checks: both callers measure ≥ 15 baseline-sitting cells on a good render.
        check(bottoms.size >= MIN_MEASURED_CELLS) {
            "baselineSpread inked only ${bottoms.size} cells (need ≥ $MIN_MEASURED_CELLS) — atlas/capture broken"
        }
        return bottoms.max() - bottoms.min()
    }

    /** Brogue's blur metric over a white/coloured-on-black panel: Σ sin(π·coverage), coverage = max RGB channel. */
    private fun panelBlur(pix: Pixmap): Double {
        var blur = 0.0
        for (y in 0 until pix.height) {
            for (x in 0 until pix.width) {
                val rgba = pix.getPixel(x, y)
                val r = (rgba ushr 24) and 0xFF
                val g = (rgba ushr 16) and 0xFF
                val b = (rgba ushr 8) and 0xFF
                val cov = maxOf(r, g, b) / 255.0
                blur += kotlin.math.sin(Math.PI * cov)
            }
        }
        return blur
    }

    /**
     * A framed dungeon + status screen through the bundled default face — the "what the engine actually
     * looks like" image. Exercises the coverage a face has to have for a roguelike: double-line box drawing
     * (which must tile — ADR-0040/0041), the shades, hearts and arrows, Greek/math, and ordinary text.
     *
     * Lived in the `fontEval` harness until krogue-tg5. That harness requires `-PfontsDir` (it exists to
     * compare *candidate* faces), so this image — which only ever used the bundled face — could not be
     * regenerated without one, and went stale silently: a copy rendered before krogue-9x7.4 was still
     * showing the pre-ADR-0040 gaps at every cell boundary long after they were fixed. It belongs here,
     * where every run refreshes it.
     *
     * Rasterised at [cell] px and written **1:1** (no viewer upscale) so the PNG is the engine's true
     * output pixels — a nearest upscale would magnify the downsample's edge AA and read as "soft".
     */
    private fun renderShowcase(outPath: String) {
        val cell = 40
        val cols = 34
        val rows = 19

        val wall = Color(0.62f, 0.60f, 0.68f, 1f)
        val floor = Color(0.34f, 0.34f, 0.40f, 1f)
        val hero = Color.WHITE
        val gold = Color(0.95f, 0.80f, 0.30f, 1f)
        val red = Color(0.90f, 0.30f, 0.30f, 1f)
        val green = Color(0.50f, 0.85f, 0.45f, 1f)
        val cyan = Color(0.45f, 0.85f, 0.95f, 1f)
        val magenta = Color(0.85f, 0.45f, 0.95f, 1f)
        val tan = Color(0.86f, 0.78f, 0.56f, 1f)

        val p = ArrayList<Placed>()

        fun put(
            c: Int,
            r: Int,
            slot: Int,
            col: Color,
        ) {
            if (slot != 32) p += Placed(c, r, slot, col)
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

        val src = Fonts.cascadiaMono(cell, cell, snapToPixelGrid = true)
        val pix = renderGrid(src, cols, rows, cell, cell, p, bg = Color(0.06f, 0.06f, 0.09f, 1f)) // disposes src
        val path =
            outPath.replaceAfterLast('/', "cascadia-showcase.png").let {
                if (it == outPath) "$outPath.showcase.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), pix, Deflater.DEFAULT_COMPRESSION, false)
        pix.dispose()
        println("FTVERIFY wrote $path (${cols * cell}x${rows * cell}, native 1:1)")
    }

    /**
     * Descender probe: a large TEXT-fit row of descender-heavy glyphs over a 1px cell-floor guide line, so
     * any baseline/descender clipping by the per-cell scissor (krogue-ns5) is unmistakable. Moved out of
     * `fontEval` alongside [renderShowcase] for the same reason — it only uses the bundled face.
     */
    private fun renderDescenderProbe(outPath: String) {
        val big = 96
        val probe = "Rogue gjpqy Happy".map { it.code }
        val placed = probe.mapIndexedNotNull { i, ch -> if (ch == 32) null else Placed(i, 0, ch, Color.WHITE) }
        val src = Fonts.cascadiaMono(big, big, snapToPixelGrid = true)
        val pix =
            renderGrid(src, probe.size, 1, big, big, placed, bg = Color(0.10f, 0.10f, 0.13f, 1f)) // disposes src
        pix.setColor(0.9f, 0.3f, 0.3f, 1f)
        pix.drawLine(0, big - 1, pix.width - 1, big - 1)
        val path =
            outPath.replaceAfterLast('/', "cascadia-descenders.png").let {
                if (it == outPath) "$outPath.desc.png" else it
            }
        PixmapIO.writePNG(Gdx.files.absolute(path), pix, Deflater.DEFAULT_COMPRESSION, false)
        pix.dispose()
        println("FTVERIFY wrote $path")
    }

    /**
     * Blits [placed] glyphs from [source] straight into a [cols]x[rows] grid of [cellW]x[cellH]px cells
     * (1:1, no window, no HiDPI scaling — so the atlas px land on FBO px and the image is genuinely
     * crisp). Each glyph is drawn tinted, over a uniform dark background, matching the source's own
     * y-up→readback orientation (the same as `renderChart`). The [source] is disposed before returning.
     */
    private fun renderGrid(
        source: FreeTypeGlyphSource,
        cols: Int,
        rows: Int,
        cellW: Int,
        cellH: Int,
        placed: List<Placed>,
        bg: Color = Color(0.09f, 0.09f, 0.12f, 1f),
    ): Pixmap {
        val w = cols * cellW
        val h = rows * cellH
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
        for (p in placed) {
            val region = source.glyph(Char(p.slot)) ?: continue
            batch.color = p.fg
            batch.draw(
                region,
                (p.col * cellW).toFloat(),
                (h - (p.row + 1) * cellH).toFloat(),
                cellW.toFloat(),
                cellH.toFloat(),
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
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) for (xx in 0 until w) flipped.drawPixel(xx, yy, raw.getPixel(xx, h - 1 - yy))
        raw.dispose()
        fbo.dispose()
        // create() makes the window own `source`; readback is done, so dispose the window here — it
        // releases its canvas/caches AND the source. Callers therefore must NOT dispose the source too.
        window.dispose()
        return flipped
    }

    /**
     * Renders the full CP437 page with a supersample=8 source (3 halving passes, odd) and checks the
     * full block (slot 219) is still opaque in its own cell — a vertically flipped atlas would put a
     * sparse '+' there. Uses the chart geometry (matches this harness window) rather than a 1x1 grid,
     * which would mis-scale here since the harness window isn't resized per-check.
     */
    private fun fullBlockOrientationCheck() {
        val ss8 = FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), CELL, CELL, 8)
        val chart = renderChart(ss8)
        val block = cellAvg(chart, 219)
        chart.dispose()
        // ss8 was disposed by renderChart's window.
        report("full block (ss=8) atlas upright, not flipped", block > 0.8f, "cellAvg(219)=$block")

        // Mirror the committed odd-supersample spec's EXACT geometry (16px cell, ss=8, snap off), which the
        // CELL=24 chart above does not — the ux6 TEXT em-shrink makes the full block a little smaller, and
        // at 16px its central-half coverage lands just under the old 0.9 gate (was 0.897 on CI). Sample the
        // block's central half the same way the spec does (averageColor(4,4,12,12) within its cell).
        val ss8At16 = FreeTypeGlyphSource(Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"), 16, 16, 8)
        val page = renderPageDirect(ss8At16, 16)
        val bx = (219 % COLS) * 16
        val by = (219 / COLS) * 16
        val block16 = page.averageColor(bx + 4, by + 4, bx + 12, by + 12).r
        page.dispose()
        report("full block (ss=8, 16px) central-half opaque (committed spec geometry)", block16 > 0.8f, "avg=$block16")
    }

    /**
     * krogue-9x7.3: verifies the TILE fit and the per-glyph brightness curve on real pixels, and dumps a
     * TILE-mode CP437 page (freetype-tile.png) to eyeball placement/orientation. Prints stats so the
     * committed GL specs can be pinned to the observed discriminators.
     */
    private fun verifyTileFitAndBrightness(outPath: String) {
        val textSrc = Fonts.cascadiaMono(CELL, CELL) // TEXT, no brightness curve (the shipped default)
        val tileSrc = Fonts.cascadiaMono(CELL, CELL, fit = GlyphFit.TILE)
        val brightSrc = Fonts.cascadiaMono(CELL, CELL, glyphBrightness = 2f)
        val bright4Src = Fonts.cascadiaMono(CELL, CELL, glyphBrightness = 4f) // max cap — blank must still stay blank

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
        // "Unchanged" means equal to the un-boosted baseline (a solid glyph's peak is already full, so
        // boost = 1) — compare against textChart within a small tolerance, not just "still bright".
        val block219Text = cellAvg(textChart, 219)
        val block219Bright = cellAvg(brightChart, 219)
        report(
            "brightness leaves the full block (219) unchanged",
            abs(block219Bright - block219Text) < 0.02f && block219Bright > 0.9f,
            "text=$block219Text bright=$block219Bright",
        )
        // ADR-0029: a blank/keyed-out glyph (space, 32) must stay transparent even at the max cap.
        report(
            "brightness keeps a blank glyph (32) transparent at cap 4",
            cellAvg(bright4Chart, 32) < 0.05f,
            "avg=${cellAvg(bright4Chart, 32)}",
        )

        // snapToPixelGrid: the CPU shift-search downsample must still yield a sane atlas (mirrors the
        // committed GL spec) — full block opaque, space empty.
        val snapChart = renderChart(Fonts.cascadiaMono(CELL, CELL, snapToPixelGrid = true))
        report("snap path: full block (219) opaque", cellAvg(snapChart, 219) > 0.9f, "avg=${cellAvg(snapChart, 219)}")
        report("snap path: space (32) empty", cellAvg(snapChart, 32) < 0.1f, "avg=${cellAvg(snapChart, 32)}")
        snapChart.dispose()

        val tilePath =
            outPath.replaceAfterLast(
                '/',
                "freetype-tile.png",
            ).let { if (it == outPath) "$outPath.tile.png" else it }
        PixmapIO.writePNG(Gdx.files.absolute(tilePath), tileChart, Deflater.DEFAULT_COMPRESSION, false)
        println("FTVERIFY wrote $tilePath")

        // The four sources were each consumed (and disposed) by their renderChart window; only the
        // returned chart pixmaps are ours to release.
        textChart.dispose()
        tileChart.dispose()
        brightChart.dispose()
        bright4Chart.dispose()
    }

    /**
     * krogue-wcw: reproduces the committed atlas-gutter GL spec's shape on macOS (which the Kotest GL suite
     * cannot run here). That spec's `renderMagnifiedSlot` blits ONE atlas region, magnified [BLEED_SCALE]×,
     * into a [BLEED_SPAN]-square capture FBO through a [BLEED_SPAN] ortho camera — so the mirror below uses
     * the same FBO size, the same camera, the same single draw, and the same measurements ([peakIn] /
     * [averageColor], shared with the spec) against the same shared thresholds.
     *
     * Neither side's geometry depends on the window: the draw goes into a capture FBO (whose `begin()` sets
     * the GL viewport to the FBO) through a camera sized from the FBO, never from `Gdx.graphics`. That is
     * also why nothing here is scaled by [hidpi] — scaling the FBO but not the camera would magnify by
     * `BLEED_SCALE × hidpi` and read a different bleed weight than CI does.
     *
     * Magnification is what makes the packing observable at all: at 1:1 every sample lands on a texel
     * centre. With the tight page this reads ~112/255 of the neighbour's ink in the empty half of '▄' and
     * drops '█'s dimmed edge strips to ~0.58 of solid; with the extruded gutter, 0 and ~1.0.
     */
    private fun verifyAtlasGutterCommittedShape() {
        // Each native resource is owned from the statement that creates it, and the capture framebuffer is
        // only ended if it was begun. This harness catches per check and moves on, so a throw that left
        // this FBO bound would silently redirect every LATER check's capture into it — the same discipline
        // TileSheetManualVerify.capture keeps, for the same reason.
        fun magnified(slot: Int): Pixmap {
            val span = BLEED_SPAN
            val source = Fonts.cascadiaMono(BLEED_CELL, BLEED_CELL)
            try {
                Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
                val fbo = FrameBuffer(Pixmap.Format.RGBA8888, span, span, false)
                var begun = false
                try {
                    fbo.begin()
                    begun = true
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    val batch = SpriteBatch()
                    try {
                        val cam =
                            OrthographicCamera().apply {
                                setToOrtho(false, span.toFloat(), span.toFloat())
                                update()
                            }
                        batch.projectionMatrix = cam.combined
                        batch.color = Color.WHITE
                        batch.begin()
                        try {
                            source.glyph(Char(slot))?.let { batch.draw(it, 0f, 0f, span.toFloat(), span.toFloat()) }
                        } finally {
                            batch.end()
                        }
                    } finally {
                        batch.dispose()
                    }
                    val raw = Pixmap.createFromFrameBuffer(0, 0, span, span)
                    return try {
                        // FBO pixels are bottom-up; the caller wants the spec's top-left origin.
                        val upright =
                            Pixmap(span, span, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
                        try {
                            for (yy in 0 until span) upright.drawPixmap(raw, 0, yy, 0, span - 1 - yy, span, 1)
                        } catch (t: Throwable) {
                            upright.dispose() // nobody owns it yet
                            throw t
                        }
                        upright
                    } finally {
                        raw.dispose()
                    }
                } finally {
                    try {
                        if (begun) fbo.end()
                    } finally {
                        fbo.dispose()
                    }
                }
            } finally {
                source.dispose()
            }
        }

        // '▄' (220): its own top half is empty and both its horizontal neighbours ('█' 219, '▌' 221) are
        // inked along the edge they share with it, so any ink up there came from another atlas cell.
        val lower = magnified(220)
        val empty = peakIn(lower, 0, 0, BLEED_SPAN, BLEED_SPAN / 2 - BLEED_SCALE)
        val ownHalf =
            lower.averageColor(0, BLEED_SPAN / 2 + BLEED_SCALE, BLEED_SPAN, BLEED_SPAN).r.toDouble()
        lower.dispose()
        report("wcw: '▄' takes no ink from its atlas neighbours", empty < BLEED_CLEAR_PEAK, "peak=$empty")
        report("wcw: '▄' still inks its own half, magnified", ownHalf > BLEED_SOLID_MEAN, "mean=$ownHalf")

        // '█' (219): solid to all four edges, with empty neighbours beyond three of them — un-fixed, the
        // sample past its edge DIMS the strip (which a merely-transparent gutter would do too).
        val block = magnified(219)
        val strips =
            mapOf(
                "left" to listOf(0, 0, BLEED_SCALE, BLEED_SPAN),
                "right" to listOf(BLEED_SPAN - BLEED_SCALE, 0, BLEED_SPAN, BLEED_SPAN),
                "top" to listOf(0, 0, BLEED_SPAN, BLEED_SCALE),
                "bottom" to listOf(0, BLEED_SPAN - BLEED_SCALE, BLEED_SPAN, BLEED_SPAN),
            )
        val means = strips.mapValues { (_, r) -> block.averageColor(r[0], r[1], r[2], r[3]).r.toDouble() }
        block.dispose()
        means.forEach { (edge, mean) ->
            report("wcw: '█' stays solid along its $edge edge", mean > BLEED_SOLID_MEAN, "mean=$mean")
        }
        println("  GUTTER ▄[emptyPeak=$empty ownHalf=$ownHalf]  █$means")
    }

    /**
     * krogue-9x7.4: reproduces the committed cell-filling/seam GL specs' shape on macOS (which the Kotest
     * GL suite cannot run here). The committed specs render through [renderSlotGrid] — a 1:1 SpriteBatch
     * blit of the source's atlas regions onto black, no AsciiTileWindow — which is exactly what [renderGrid]
     * does here, so the two agree on geometry: same [SEAM_CELL] square cell, same 2-cell strips, same
     * single-cell blocks, and the same measurements ([columnPeaks]/[rowPeaks]/[averageColor], shared with
     * the spec rather than re-implemented) against the same shared thresholds.
     *
     * The square cell is the whole point: Cascadia Mono's design cell is tall, so a square target cell is
     * the "cell aspect != the font's" case the edge-snapped placement exists for.
     */
    private fun verifyCellFillSeamsCommittedShape() {
        fun grid(
            slots: List<Int>,
            cols: Int,
            rows: Int,
            snap: Boolean = false,
        ): Pixmap {
            val source =
                Fonts.cascadiaMono(SEAM_CELL, SEAM_CELL, snapToPixelGrid = snap)
            val placed = slots.mapIndexed { i, slot -> Placed(i % cols, i / cols, slot, Color.WHITE) }
            return renderGrid(source, cols, rows, SEAM_CELL, SEAM_CELL, placed, bg = Color.BLACK) // disposes source
        }

        for (snap in listOf(false, true)) {
            val tag = if (snap) "snap" else "plain"
            // '─' across two horizontally adjacent cells: every column must carry the stroke (min peak high),
            // and rows away from it must stay dark (so the check can't pass on an all-white render).
            val h = grid(listOf(196, 196), cols = 2, rows = 1, snap = snap)
            val hCols = columnPeaks(h).min()
            val hRows = rowPeaks(h).min()
            h.dispose()
            report("9x7.4 [$tag]: '─' unbroken across the cell seam", hCols > SEAM_INKED_PEAK, "minCol=$hCols")
            report("9x7.4 [$tag]: '─' is a line, not a filled cell", hRows < SEAM_BLANK_PEAK, "minRow=$hRows")

            // '│' down two vertically adjacent cells — the same, one axis over.
            val v = grid(listOf(179, 179), cols = 1, rows = 2, snap = snap)
            val vRows = rowPeaks(v).min()
            val vCols = columnPeaks(v).min()
            v.dispose()
            report("9x7.4 [$tag]: '│' unbroken across the cell seam", vRows > SEAM_INKED_PEAK, "minRow=$vRows")
            report("9x7.4 [$tag]: '│' is a line, not a filled cell", vCols < SEAM_BLANK_PEAK, "minCol=$vCols")
            println("  CELLFILL $tag ${SEAM_CELL}px  ─[minCol=$hCols minRow=$hRows]  │[minRow=$vRows minCol=$vCols]")
        }

        // '█' fills its whole cell; '▄'/'▀' fill exactly their half (which also pins the map's orientation —
        // a y-mirrored read of Glyph.yoffset would swap them and still fill the cell).
        val block = grid(listOf(219), cols = 1, rows = 1)
        val blockFill = block.averageColor(0, 0, SEAM_CELL, SEAM_CELL).r
        block.dispose()
        report("9x7.4: '█' fills its whole cell (>0.95)", blockFill > 0.95f, "fill=$blockFill")

        val half = SEAM_CELL / 2
        val lower = grid(listOf(220), cols = 1, rows = 1)
        val lowerBottom = lower.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r
        val lowerTop = lower.averageColor(0, 0, SEAM_CELL, half - 1).r
        lower.dispose()
        report("9x7.4: '▄' inks the cell's bottom half (>0.95)", lowerBottom > 0.95f, "bottom=$lowerBottom")
        report("9x7.4: '▄' leaves the top half empty (<0.05)", lowerTop < 0.05f, "top=$lowerTop")

        val upper = grid(listOf(223), cols = 1, rows = 1)
        val upperTop = upper.averageColor(0, 0, SEAM_CELL, half - 1).r
        val upperBottom = upper.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r
        upper.dispose()
        report("9x7.4: '▀' inks the cell's top half (>0.95)", upperTop > 0.95f, "top=$upperTop")
        report("9x7.4: '▀' leaves the bottom half empty (<0.05)", upperBottom < 0.05f, "bottom=$upperBottom")
        println(
            "  CELLFILL blocks  █=$blockFill  ▄[bottom=$lowerBottom top=$lowerTop]" +
                " ▀[top=$upperTop bottom=$upperBottom]",
        )

        // krogue-5uw: the SAME two checks with snap ON — mirroring the committed
        // "half blocks keep their orientation" spec, from the same `grid` helper, cell size and sample
        // rects, differing only in `snap = true`. This is the pair that reads the master through
        // `bottomUpRowStart`; the plain pair above never runs that code, so only these go red if the row
        // inversion is dropped and the page comes out vertically mirrored.
        val snapLower = grid(listOf(220), cols = 1, rows = 1, snap = true)
        val snapLowerBottom = snapLower.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r
        val snapLowerTop = snapLower.averageColor(0, 0, SEAM_CELL, half - 1).r
        snapLower.dispose()
        report(
            "5uw [snap]: '▄' inks the cell's bottom half (>0.95)",
            snapLowerBottom > 0.95f,
            "bottom=$snapLowerBottom",
        )
        report("5uw [snap]: '▄' leaves the top half empty (<0.05)", snapLowerTop < 0.05f, "top=$snapLowerTop")

        val snapUpper = grid(listOf(223), cols = 1, rows = 1, snap = true)
        val snapUpperTop = snapUpper.averageColor(0, 0, SEAM_CELL, half - 1).r
        val snapUpperBottom = snapUpper.averageColor(0, half + 1, SEAM_CELL, SEAM_CELL).r
        snapUpper.dispose()
        report("5uw [snap]: '▀' inks the cell's top half (>0.95)", snapUpperTop > 0.95f, "top=$snapUpperTop")
        report(
            "5uw [snap]: '▀' leaves the bottom half empty (<0.05)",
            snapUpperBottom < 0.05f,
            "bottom=$snapUpperBottom",
        )
        println(
            "  CELLFILL blocks [snap]  ▄[bottom=$snapLowerBottom top=$snapLowerTop]" +
                " ▀[top=$snapUpperTop bottom=$snapUpperBottom]",
        )
    }

    /**
     * krogue-tg5: reproduces the committed stroke-snap GL specs' shape on macOS (which the Kotest GL suite
     * cannot run here). Those specs render through `renderSlotGrid` — a 1:1 SpriteBatch blit of the source's
     * atlas regions onto black, no AsciiTileWindow — which is exactly what [renderGrid] does, so the two
     * agree on geometry: same square [SEAM_CELL] cell, same single cells and `─ ┼ ─` strip, and the same
     * measurements ([rowPeaks]/[columnPeaks]/[strokeRows]) against the same shared thresholds.
     *
     * Also sweeps cell sizes and prints the half-lit (neither solid nor blank) line count per size, which is
     * how the committed thresholds were chosen: run this with the production change stashed to see the
     * un-fixed numbers it is supposed to fail on.
     */
    private fun verifyStrokeSnapCommittedShape() {
        fun grid(
            slots: List<Int>,
            cols: Int,
            cell: Int = SEAM_CELL,
        ): Pixmap {
            val source = Fonts.cascadiaMono(cell, cell, snapToPixelGrid = true)
            val placed = slots.mapIndexed { i, slot -> Placed(i, 0, slot, Color.WHITE) }
            return renderGrid(source, cols, 1, cell, cell, placed, bg = Color.BLACK) // disposes source
        }

        fun halfLit(peaks: List<Int>) = peaks.count { it in (SNAP_BLANK_PEAK + 1) until SNAP_SOLID_PEAK }

        // The committed single-cell assertions: every line of the cell is solid or blank, never half-lit —
        // and both kinds are present, so an empty or a filled cell can't satisfy it vacuously.
        val hCell = grid(listOf(196), cols = 1)
        val hPeaks = rowPeaks(hCell)
        hCell.dispose()
        val vCell = grid(listOf(179), cols = 1)
        val vPeaks = columnPeaks(vCell)
        vCell.dispose()
        for ((tag, peaks) in listOf("─ rows" to hPeaks, "│ cols" to vPeaks)) {
            report("tg5: '$tag' has a solid line", peaks.count { it >= SNAP_SOLID_PEAK } > 0, "$peaks")
            report("tg5: '$tag' has a blank line", peaks.count { it <= SNAP_BLANK_PEAK } > 0, "$peaks")
            report("tg5: '$tag' has no half-lit line", halfLit(peaks) == 0, "halfLit=${halfLit(peaks)} $peaks")
        }

        // The committed mixed-seam assertions: a cross must snap the arm it shares with the plain line to
        // the SAME rows, or a frame would step at every junction. Single line, then double line (two runs
        // per axis). Measured in each cell's left quarter, clear of the cross's stem.
        for (family in listOf(listOf(196, 197, 196), listOf(205, 206, 205))) {
            val tag = family.joinToString("") { Char(it).toString() }
            val strip = grid(family, cols = 3)
            val quarter = SEAM_CELL / 4
            val rows = (0 until 3).map { i -> strokeRows(strip, i * SEAM_CELL, i * SEAM_CELL + quarter) }
            // Only the SEAM columns: '╬' is four corner pieces around a hollow centre, so its middle
            // columns are legitimately blank and a whole-strip minimum would fail on a correct render.
            val cols = columnPeaks(strip)
            val seams = listOf(SEAM_CELL, 2 * SEAM_CELL).flatMap { listOf(cols[it - 1], cols[it]) }
            strip.dispose()
            report("tg5: '$tag' arm is present to compare", rows[0].isNotEmpty(), "${rows[0]}")
            report("tg5: '$tag' arms agree across both seams", rows[1] == rows[0] && rows[2] == rows[0], "$rows")
            report("tg5: '$tag' inked on both sides of each seam", seams.min() > SEAM_INKED_PEAK, "$seams")
        }

        // Size sweep (evidence, not an assertion): half-lit line counts across cell sizes.
        for (cell in listOf(12, 16, 20, SEAM_CELL, 30, 32)) {
            val h = grid(listOf(196), cols = 1, cell = cell)
            val hp = rowPeaks(h)
            h.dispose()
            val v = grid(listOf(179), cols = 1, cell = cell)
            val vp = columnPeaks(v)
            v.dispose()
            println(
                "  STROKESNAP cell=$cell halfLit ─=${halfLit(hp)} │=${halfLit(vp)}" +
                    "  ─${hp.filter { it > 0 }} │${vp.filter { it > 0 }}",
            )
        }
    }

    /**
     * krogue-ux6: reproduces the committed top-clip GL spec's shape on macOS (which the Kotest GL suite
     * cannot run here). The clip the spec guards against happens **in the atlas** — the per-cell scissor in
     * rasterize — and the committed `glyphCell` just blits that atlas region 1:1 into a cell-sized buffer
     * (on CI, hidpi=1). So a direct 1:1 region blit of the atlas at cell px (no AsciiTileWindow, no HiDPI
     * scaling) measures exactly what CI's `glyphCell` measures. Renders the CP437 page at a 32px cell and
     * asserts the ascender 'h' and the accented cap 'Å' clear the ceiling (top-row / top-two-row peak below
     * the threshold) while their bodies stay strongly inked. Verified new-vs-old at this geometry: the
     * ascender's top row is a flat stem cut (peak 1.0) with the un-fixed cap-box centring and 0.0 after the
     * em-fit; the accented cap's top two rows drop ~0.71 -> ~0.04.
     */
    private fun verifyTopClipCommittedShape() {
        val cell = 32
        val page = renderPageDirect(Fonts.cascadiaMono(cell, cell, snapToPixelGrid = true), cell)

        fun peakRow(
            slot: Int,
            y0: Int,
            y1: Int,
        ): Float {
            val cx = (slot % COLS) * cell
            val cy = (slot / COLS) * cell
            var peak = 0f
            for (y in y0 until y1) {
                for (x in cell / 4 until cell * 3 / 4) {
                    peak = maxOf(peak, page.averageColor(cx + x, cy + y, cx + x + 1, cy + y + 1).r)
                }
            }
            return peak
        }

        // Ascender 'h': hard stem cut => top row fully inked when clipped, empty when it clears.
        val hBody = peakRow('h'.code, cell / 2, cell - 3)
        val hCeil = peakRow('h'.code, 0, 1)
        report("ux6: ascender 'h' body inked (>0.5)", hBody > 0.5f, "body=$hBody")
        report("ux6: ascender 'h' clears the ceiling (<0.5, was ~1.0)", hCeil < 0.5f, "ceil=$hCeil")
        // Accented cap 'Å' (slot 143), the headline case: its ring clears the ceiling too — the top two rows
        // were ~0.71 (jammed against the top) and taper to ~0.04 after the fit.
        val aBody = peakRow(143, 4, cell * 3 / 8)
        val aCeil = peakRow(143, 0, 2)
        report("ux6: accented cap 'Å' body inked (>0.4)", aBody > 0.4f, "body=$aBody")
        report("ux6: accented cap 'Å' clears the ceiling (<0.5, was ~0.71)", aCeil < 0.5f, "ceil=$aCeil")
        // Descender 'g' floor (ns5, still held by ux6): tail tapers above the floor rather than a flat cut.
        val gFloor = peakRow('g'.code, cell - 1, cell)
        report("ux6/ns5: descender 'g' still clears the floor (<0.5)", gFloor < 0.5f, "floor=$gFloor")
        println(
            "  UX6 top-clip 32px  h[body=%.2f ceil=%.2f]  Å[body=%.2f ceil=%.2f]  g[floor=%.2f]".format(
                hBody,
                hCeil,
                aBody,
                aCeil,
                gFloor,
            ),
        )
        page.dispose()
    }

    /**
     * krogue-9x7.6: reproduces the committed shift-cache GL specs' shape on macOS (which the Kotest GL suite
     * cannot run here). Same source config (`Fonts.cascadiaMono` at [CACHE_CELL] with `snapToPixelGrid`,
     * TEXT then TILE), the same one-source [CACHE_CELL] → [CACHE_OTHER_CELL] → [CACHE_CELL] round trip, the
     * same shared literals, the same 1:1 CP437 page blit into an FBO the source's own rasterise re-binds
     * across (the spec's capture FBO is likewise bound while `prepareForCellSize` runs), and the same
     * [exactDiff]. The spec reads `lastShiftSearchCount` after each rasterise and requires
     * `first > 0, other > 0, return == 0` with a zero pixel diff; so does this.
     *
     * Also prints the wall-clock of the searching vs the cached rasterise — evidence for the bead's premise
     * (the search is the expensive part of a resize), not an assertion: it is one untimed run on a warm JIT,
     * not a benchmark.
     */
    private fun verifyShiftCacheCommittedShape() {
        for (fit in listOf(GlyphFit.TEXT, GlyphFit.TILE)) {
            val src = Fonts.cascadiaMono(CACHE_CELL, CACHE_CELL, fit = fit, snapToPixelGrid = true)
            val atFirst = src.lastShiftSearchCount
            val before = renderPageDirect(src, CACHE_CELL, disposeSource = false)

            val searchedNanos =
                measureNanoTime { src.prepareForCellSize(CACHE_OTHER_CELL, CACHE_OTHER_CELL) }
            val atOther = src.lastShiftSearchCount
            src.prepareForCellSize(CACHE_CELL, CACHE_CELL)
            val onReturn = src.lastShiftSearchCount
            // Same size as the timed searching rasterise above, now a cache hit — the only apples-to-apples
            // pair (CACHE_CELL and CACHE_OTHER_CELL rasterise different amounts of pixels).
            val cachedNanos =
                measureNanoTime { src.prepareForCellSize(CACHE_OTHER_CELL, CACHE_OTHER_CELL) }
            src.prepareForCellSize(CACHE_CELL, CACHE_CELL) // back to the captured geometry

            val after = renderPageDirect(src, CACHE_CELL, disposeSource = true)
            val (diff, inked) = exactDiff(before, after)
            before.dispose()
            after.dispose()

            val counts = "first=$atFirst other=$atOther return=$onReturn"
            report("9x7.6[$fit]: a fresh cell size runs the search", atFirst > 0, counts)
            report("9x7.6[$fit]: a different cell size searches again", atOther > 0, counts)
            report("9x7.6[$fit]: a revisited cell size does not search", onReturn == 0, counts)
            report("9x7.6[$fit]: the page has ink to compare", inked > 0, "inked=$inked")
            report("9x7.6[$fit]: cached offsets reproduce the atlas exactly", diff == 0, "diff=$diff/$inked")
            println(
                "  SHIFTCACHE fit=$fit  %dpx rasterise: searched=%.0fms cached=%.0fms".format(
                    CACHE_OTHER_CELL,
                    searchedNanos / 1e6,
                    cachedNanos / 1e6,
                ),
            )
        }

        // Size sweep (evidence, not an assertion): the same searched-vs-cached pair at larger cells, where
        // the search's ss² × cell² grid is a bigger share of the rasterise. A fresh source per size so the
        // first rasterise is a genuine miss; the scratch size in between forces the re-rasterise.
        for (cell in listOf(24, 32, 48)) {
            // Construct at the SCRATCH size: constructing at `cell` would rasterise (and cache) it, and the
            // "searched" timing below would silently be a cache hit.
            val src = Fonts.cascadiaMono(cell + 1, cell + 1, snapToPixelGrid = true)
            val searched = measureNanoTime { src.prepareForCellSize(cell, cell) }
            src.prepareForCellSize(cell + 1, cell + 1)
            val cached = measureNanoTime { src.prepareForCellSize(cell, cell) }
            println(
                "  SHIFTCACHE sweep cell=%d  searched=%.0fms  cached=%.0fms".format(
                    cell,
                    searched / 1e6,
                    cached / 1e6,
                ),
            )
            src.dispose()
        }
    }

    /**
     * Draws all 256 CP437 slots of [source] white-on-black 1:1 at [cell] px into an FBO via a direct
     * SpriteBatch region blit (no AsciiTileWindow, no HiDPI scaling — the FBO is exactly COLS·[cell] ×
     * ROWS·[cell] and the ortho matches, so atlas px land on FBO px). Returns the upright page pixmap and
     * **disposes [source]** unless [disposeSource] is false. This is the atlas the window blits, so it
     * mirrors what CI's `glyphCell` / page-render specs measure (hidpi=1). Build [source] BEFORE calling
     * (its rasterise binds its own FBOs).
     */
    private fun renderPageDirect(
        source: FreeTypeGlyphSource,
        cell: Int,
        disposeSource: Boolean = true,
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
            val region = source.glyph(Char(slot)) ?: continue
            batch.draw(
                region,
                ((slot % COLS) * cell).toFloat(),
                (h - (slot / COLS + 1) * cell).toFloat(),
                cell.toFloat(),
                cell.toFloat(),
            )
        }
        batch.end()
        val raw = Pixmap.createFromFrameBuffer(0, 0, w, h)
        fbo.end()
        batch.dispose()
        // The shift-cache mirror (krogue-9x7.6) captures the SAME source twice, so it keeps it alive.
        if (disposeSource) source.dispose()
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (yy in 0 until h) flipped.drawPixmap(raw, 0, yy, 0, h - 1 - yy, w, 1)
        raw.dispose()
        fbo.dispose()
        return flipped
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

    /**
     * Verifies `boxAlignedGlyphs` (krogue-jdeu): a glyph named there must sit on the **same** horizontal
     * line as the box-drawing stroke it is set into, and must visibly *not* without it.
     *
     * The case is a roguelike drawing a door as `+` in a run of connected wall. Box-drawing is placed by
     * mapping a design cell onto the cell; everything else is placed from text metrics — two independent
     * rules, so by default the door floats above the wall even though the face itself aligns the two
     * (Cascadia puts both at vertical centre 0.346em). Measured here as the alpha-weighted vertical
     * centroid of each glyph's cell, which is where the eye reads the stroke.
     */
    private fun verifyBoxAlignedGlyphs() {
        val cell = 48
        val plus = '+'.code // CP437 slot 0x2B; ASCII slots and code points coincide, so this is both

        fun centroidsAt(boxAligned: Set<Int>): Pair<Double, Double> {
            val source =
                FreeTypeGlyphSource(
                    Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                    cell,
                    cell,
                    snapToPixelGrid = true,
                    boxAlignedGlyphs = boxAligned,
                )
            // Slot 0x2B is '+', slot 0xC4 is CP437's box-drawing horizontal ─.
            val placed = listOf(Placed(0, 0, 0x2B, Color.WHITE), Placed(1, 0, 0xC4, Color.WHITE))
            val map = renderGrid(source, 2, 1, cell, cell, placed, bg = Color.BLACK)
            return try {
                verticalCentroid(map, 0, cell) to verticalCentroid(map, 1, cell)
            } finally {
                map.dispose()
            }
        }

        val (plusOff, wallOff) = centroidsAt(emptySet())
        val (plusOn, wallOn) = centroidsAt(setOf(plus))
        val driftOff = plusOff - wallOff
        val driftOn = plusOn - wallOn
        report(
            "boxAlignedGlyphs off: '+' drifts from the wall line",
            kotlin.math.abs(driftOff) > 1.0,
            "drift ${"%.2f".format(driftOff)}px of a ${cell}px cell (the defect this fixes)",
        )
        report(
            "boxAlignedGlyphs on: '+' lands on the wall line",
            kotlin.math.abs(driftOn) <= 0.5,
            "drift ${"%.2f".format(driftOn)}px of a ${cell}px cell",
        )

        // Aligning must not distort: the design cell is a tall rectangle, so stretching a shaped glyph
        // onto a square cell squashes it. '+' is symmetric, so its ink box should come out near square.
        val source =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                cell,
                cell,
                snapToPixelGrid = true,
                boxAlignedGlyphs = setOf(plus),
            )
        val map = renderGrid(source, 1, 1, cell, cell, listOf(Placed(0, 0, 0x2B, Color.WHITE)), bg = Color.BLACK)
        val aspect =
            try {
                inkAspect(map, cell)
            } finally {
                map.dispose()
            }
        // '+' cannot prove the slot/code-point distinction: 0x2B is the same number either way, so the
        // older implementation that read this set as Unicode behaves identically on it. Slot 0xE0 renders
        // α (U+03B1), so naming it only has an effect when the set is compared as CP437 slots — this
        // check fails against that implementation and passes against this one.
        val alphaSlot = 0xE0

        fun centroidOfSlot(
            slot: Int,
            boxAligned: Set<Int>,
        ): Double {
            val source =
                FreeTypeGlyphSource(
                    Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                    cell,
                    cell,
                    snapToPixelGrid = true,
                    boxAlignedGlyphs = boxAligned,
                )
            val map = renderGrid(source, 1, 1, cell, cell, listOf(Placed(0, 0, slot, Color.WHITE)), bg = Color.BLACK)
            return try {
                verticalCentroid(map, 0, cell)
            } finally {
                map.dispose()
            }
        }

        val alphaPlain = centroidOfSlot(alphaSlot, emptySet())
        val alphaNamed = centroidOfSlot(alphaSlot, setOf(alphaSlot))
        report(
            "boxAlignedGlyphs is keyed by CP437 slot, not Unicode",
            kotlin.math.abs(alphaNamed - alphaPlain) > 0.5,
            "naming slot 0x${alphaSlot.toString(16)} (U+03B1) moved it " +
                "${"%.2f".format(alphaNamed - alphaPlain)}px; keyed by code point it would not move",
        )

        report(
            "boxAlignedGlyphs on: '+' keeps its aspect",
            aspect in 0.75..1.35,
            "ink w/h = ${"%.2f".format(aspect)} (anisotropic design-cell stretch would read ~2.3)",
        )

        // Aligning must not resize either. Aspect cannot catch that — it is scale-invariant, so a glyph
        // shrunk uniformly still reads ~0.95 — which is how krogue-okx2 shipped: the design cell is
        // taller than the em, so scaling by it drew every aligned glyph at ~75% of its size in prose.
        // Compare against the same glyph unaligned, which is by definition its natural size.
        val plainHeight = inkHeightOf(0x2B, cell, emptySet())
        val alignedHeight = inkHeightOf(0x2B, cell, setOf(plus))
        val sizeRatio = alignedHeight.toDouble() / plainHeight
        report(
            "boxAlignedGlyphs on: '+' keeps its size",
            sizeRatio in 0.9..1.1,
            "aligned ink is ${alignedHeight}px vs ${plainHeight}px unaligned " +
                "(${"%.2f".format(sizeRatio)}x; scaling by the design cell would read ~0.75)",
        )
    }

    /**
     * Ink height in px of one [slot] rendered at a [cell]-px cell with [boxAligned] named.
     *
     * At [rogueTextFill], not the default fill: the design cell scales with the em, so at `1f` it is only
     * ~1.04 cells and scaling by it shrinks a glyph by ~4% — too little to separate from rounding. At the
     * fill a consumer actually ships it is ~1.33 cells and the shrink is ~25%, which is the defect.
     */
    private fun inkHeightOf(
        slot: Int,
        cell: Int,
        boxAligned: Set<Int>,
    ): Int {
        val source =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                cell,
                cell,
                snapToPixelGrid = true,
                textFill = rogueTextFill,
                boxAlignedGlyphs = boxAligned,
            )
        val map = renderGrid(source, 1, 1, cell, cell, listOf(Placed(0, 0, slot, Color.WHITE)), bg = Color.BLACK)
        return try {
            val rows =
                (0 until cell).filter { y ->
                    (0 until cell).any { x -> ((map.getPixel(x, y) ushr 24) and 0xFF) > 24 }
                }
            if (rows.isEmpty()) 0 else rows.max() - rows.min() + 1
        } finally {
            map.dispose()
        }
    }

    /** Width/height of the inked box in a single [cell]-px cell at (0,0) of [map]. */
    private fun inkAspect(
        map: Pixmap,
        cell: Int,
    ): Double {
        var x0 = cell
        var x1 = -1
        var y0 = cell
        var y1 = -1
        for (y in 0 until cell) {
            for (x in 0 until cell) {
                if (((map.getPixel(x, y) ushr 24) and 0xFF) > 24) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return -1.0
        return (x1 - x0 + 1).toDouble() / (y1 - y0 + 1)
    }

    /**
     * Verifies that a run of connected wall draws its horizontal stroke on the **same output rows** in
     * every cell — corners included. Under [FreeTypeGlyphSource]'s per-cell stroke snapping each cell
     * rounds its own stroke edges, and a corner's vertical profile has a different edge set from a plain
     * `─`, so the two can round to different rows and leave a one-pixel step in the middle of a wall.
     *
     * Checked on both walls because they are not symmetric: a top corner's stem descends from the stroke
     * while a bottom corner's rises to it, so they present different edges to the snap.
     */
    private fun verifyWallRunContinuity() {
        // The cell sizes a consumer actually rasterises at — korogue-rogue's 1x-4x zoom ladder on a
        // HiDPI display (8/16/24/32pt logical, doubled for the backbuffer).
        for (cs in listOf(16, 32, 48, 64)) {
            val top = stepForCell(cs, 0xDA, 0xC4, 0xBF, rogueTextFill) // ┌ ─ ─ ┐
            val bottom = stepForCell(cs, 0xC0, 0xC4, 0xD9, rogueTextFill) // └ ─ ─ ┘
            report("wall seam, top, ${cs}px cell", top == 0, "misaligns by ${top}px")
            if (cs == 48) {
                // krogue-4ari: this size steps at every textFill, including the default. Bounded rather
                // than merely printed — asserting only `== 0` elsewhere would let this one grow from a
                // 1px step to a 6px one and still report green.
                report(
                    "wall seam, bottom, ${cs}px cell (known, krogue-4ari)",
                    bottom <= knownBottomSeamStep,
                    "misaligns by ${bottom}px, known ceiling ${knownBottomSeamStep}px",
                )
            } else {
                report("wall seam, bottom, ${cs}px cell", bottom == 0, "misaligns by ${bottom}px")
            }
        }
    }

    /**
     * Worst seam misalignment, in output pixels, across a `left mid mid right` wall run drawn at
     * [cellPx] with [fill]. Zero means every cell puts its horizontal stroke on the same rows.
     */
    private fun stepForCell(
        cellPx: Int,
        left: Int,
        mid: Int,
        right: Int,
        fill: Float,
    ): Int {
        val source =
            FreeTypeGlyphSource(
                Gdx.files.classpath("fonts/CascadiaMono-Bold.ttf"),
                cellPx,
                cellPx,
                snapToPixelGrid = true,
                textFill = fill,
            )
        val placed =
            listOf(
                Placed(0, 0, left, Color.WHITE),
                Placed(1, 0, mid, Color.WHITE),
                Placed(2, 0, mid, Color.WHITE),
                Placed(3, 0, right, Color.WHITE),
            )
        val map = renderGrid(source, 4, 1, cellPx, cellPx, placed, bg = Color.BLACK)
        return try {
            // Compare the inked rows either side of each cell boundary — the seam the eye reads. At the
            // boundary only the horizontal stroke is present, so a corner's vertical stem is not sampled.
            fun rowsAt(x: Int): List<Int> =
                (0 until cellPx).filter { y -> ((map.getPixel(x, y) ushr 24) and 0xFF) > 24 }
            (1 until 4).maxOf { boundary ->
                val l = rowsAt(boundary * cellPx - 1)
                val r = rowsAt(boundary * cellPx)
                if (l.isEmpty() || r.isEmpty()) {
                    0
                } else {
                    maxOf(kotlin.math.abs(l.min() - r.min()), kotlin.math.abs(l.max() - r.max()))
                }
            }
        } finally {
            map.dispose()
        }
    }

    /** Alpha-weighted vertical centroid of cell [col] in a single-row [map] of [cell]-px cells. */
    private fun verticalCentroid(
        map: Pixmap,
        col: Int,
        cell: Int,
    ): Double {
        var weighted = 0.0
        var total = 0.0
        for (y in 0 until cell) {
            var row = 0.0
            for (x in 0 until cell) {
                // White-on-black: the red channel carries coverage.
                row += ((map.getPixel(col * cell + x, y) ushr 24) and 0xFF).toDouble()
            }
            weighted += y * row
            total += row
        }
        return if (total == 0.0) -1.0 else weighted / total
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
