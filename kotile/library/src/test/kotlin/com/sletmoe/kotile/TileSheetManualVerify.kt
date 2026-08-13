package com.sletmoe.kotile

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
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileScaling
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * macOS-only manual verification for the artist-tilesheet glyph source (krogue-9x7.7). The Kotest GL specs
 * ([TileSheetGlyphSourceIntegrationTest]) can't run on macOS — GLFW needs the process's first thread,
 * which Gradle test workers don't own — so this boots a real GL context on the main thread via
 * `-XstartOnFirstThread` and reproduces those specs' **geometry and GL state**: the same synthetic sheet,
 * the same window size *per check* (resized and settled before each, as [HeadlessGl] does), the same
 * capture-FBO wrapping (a non-zero framebuffer handle, cleared the same way), the same draw order and the
 * same sample rects — scaled by the retina backbuffer ratio, which is 1 on CI, where the harness reduces
 * exactly to the specs.
 *
 * It also dumps a PNG of every check's capture so the tiles can be eyeballed, not just asserted.
 *
 *   ./gradlew :kotile:library:tileSheetVerify
 *
 * When this and the committed spec disagree, neither result stands: investigate both.
 */
private class TileSheetManualVerify : ApplicationAdapter() {
    private companion object {
        /** Frames a requested window size gets before the harness stops waiting for the WM and proceeds. */
        const val RESIZE_FRAMES = 120
    }

    private class Check(
        val name: String,
        val width: Int,
        val height: Int,
        val run: (Int) -> Unit,
    )

    /** Read by [main] once the GL loop ends, so a failed check fails the Gradle task. */
    var failures = 0
        private set

    /** False if the loop ended early (a crash, or the window never came up), which is also a failure. */
    var completed = false
        private set

    private var index = 0
    private var settled = 0
    private var waited = 0
    private var sheet: FileHandle? = null
    private val shots = mutableListOf<Pair<String, Pixmap>>()

    private val checks =
        listOf(
            Check("coverage sheet tints per cell, empty tile stays clear", 64, 16, ::coverageTintCheck),
            Check("FULL_COLOR carries the sheet's colour", 32, 16, ::fullColorCheck),
            Check("PRESERVE_ASPECT letterboxes a square tile", 16, 32, ::preserveAspectCheck),
            Check("prepareForCellSize re-downscales; same size is a no-op", 1, 1, ::resizeCheck),
            Check("a sheet that doesn't divide into whole tiles is rejected", 1, 1, ::rejectCheck),
            Check(
                "a magnified tile takes no ink from its atlas neighbour",
                BLEED_TILE_SPAN,
                BLEED_TILE_SPAN,
                ::atlasGutterCheck,
            ),
        )

    override fun render() {
        if (index >= checks.size) {
            dumpShots()
            completed = true
            println(if (failures == 0) "TSVERIFY: ALL PASSED" else "TSVERIFY: $failures FAILED")
            Gdx.app.exit()
            return
        }
        val check = checks[index]
        // Resize and let the resize settle, so each check runs at its spec's window size (HeadlessGl
        // resizes the shared window the same way before every render). A window manager is free to refuse
        // a size — the 1x1 checks are the obvious candidates, since most WMs clamp to a minimum — so stop
        // waiting after a deadline rather than spinning here forever.
        if (Gdx.graphics.width != check.width || Gdx.graphics.height != check.height) {
            if (waited++ < RESIZE_FRAMES) {
                Gdx.graphics.setWindowedMode(check.width, check.height)
                settled = 0
                return
            }
            // Do NOT run the check at the wrong size. The capture FBO is sized from the check rather than
            // the window, so the pixels might still come out right — but geometry parity is the entire
            // reason this harness exists: a 40x40 window once turned the resize(40, 40) under test into a
            // no-op and nearly produced a confidently wrong rebuttal to a reviewer. A check that could not
            // be run at its spec's size is a failure, not a footnote under "ALL PASSED".
            report(
                check.name,
                false,
                "not run: window stayed ${Gdx.graphics.width}x${Gdx.graphics.height}, wanted " +
                    "${check.width}x${check.height} after $RESIZE_FRAMES frames -- geometry parity lost",
            )
            index++
            settled = 0
            waited = 0
            return
        }
        if (settled++ < 2) return
        waited = 0
        // Round the backbuffer:logical ratio instead of truncating it: integer division turns a 1.5x
        // display scale into 1, so the capture FBO would be built at half the resolution the window
        // actually renders and every sample rect would read the wrong pixels.
        val hidpi =
            (Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width.coerceAtLeast(1))
                .roundToInt()
                .coerceAtLeast(1)
        if (index == 0) {
            println("TSVERIFY logical=${Gdx.graphics.width}x${Gdx.graphics.height} hidpi=$hidpi")
        }
        try {
            check.run(hidpi)
        } catch (t: Throwable) {
            report(check.name, false, "threw ${t::class.simpleName}: ${t.message}")
        }
        index++
        settled = 0
    }

    // ── The checks, one per committed spec ─────────────────────────────────────────────────────────

    /** Mirrors "a coverage sheet tints per cell and an empty tile stays clear". */
    private fun coverageTintCheck(hidpi: Int) {
        var regionSize = -1 to -1
        var masterSize = -1 to -1
        var tiles = -1
        var outOfRange = true
        val pixels =
            capture(64, 16, hidpi) {
                val source = TileSheetGlyphSource(sheet(), columns = 2, rows = 2, 16, 16)
                val window =
                    AsciiTileWindow.create {
                        glyphSource = source
                        widthInTiles = 4
                        heightInTiles = 1
                        fitToWindow = false
                    }
                try {
                    window.drawTile(0, 0, StaticAsciiTile(Char(0), Color.RED, Color.BLACK))
                    window.drawTile(1, 0, StaticAsciiTile(Char(2), Color.RED, Color.BLACK))
                    window.render()
                    val first = source.glyph(Char(0))
                    regionSize = (first?.regionWidth ?: -1) to (first?.regionHeight ?: -1)
                    masterSize = source.masterTileWidthPx to source.masterTileHeightPx
                    tiles = source.tileCount
                    outOfRange = source.glyph(Char(4)) != null
                } finally {
                    window.dispose()
                }
            }
        shots += "coverage" to pixels
        val inked = pixels.averageColor(0, 0, 16 * hidpi, 16 * hidpi)
        val empty = pixels.averageColor(16 * hidpi, 0, 32 * hidpi, 16 * hidpi)
        report(
            checks[index].name,
            regionSize == (16 to 16) &&
                masterSize == (64 to 64) &&
                tiles == 4 &&
                !outOfRange &&
                near(inked.r, 1f) &&
                near(inked.g, 0f) &&
                near(inked.b, 0f) &&
                near(empty.r, 0f) &&
                near(empty.g, 0f) &&
                near(empty.b, 0f),
            "region=$regionSize master=$masterSize tiles=$tiles outOfRange=$outOfRange " +
                "inked=(${inked.r}, ${inked.g}, ${inked.b}) empty=(${empty.r}, ${empty.g}, ${empty.b})",
        )
    }

    /** Mirrors "FULL_COLOR carries the sheet's own colour through the downscale". */
    private fun fullColorCheck(hidpi: Int) {
        val pixels =
            capture(32, 16, hidpi) {
                val source =
                    TileSheetGlyphSource(sheet(), columns = 2, rows = 2, 16, 16, ink = TileInk.FULL_COLOR)
                val window =
                    AsciiTileWindow.create {
                        glyphSource = source
                        widthInTiles = 2
                        heightInTiles = 1
                        fitToWindow = false
                    }
                try {
                    window.drawTile(0, 0, StaticAsciiTile(Char(3), Color.WHITE, Color.BLACK))
                    window.render()
                } finally {
                    window.dispose()
                }
            }
        shots += "fullcolor" to pixels
        val inked = pixels.averageColor(0, 0, 16 * hidpi, 16 * hidpi)
        report(
            checks[index].name,
            near(inked.b, 1f) && near(inked.r, 0f),
            "inked=(${inked.r}, ${inked.g}, ${inked.b})",
        )
    }

    /** Mirrors "PRESERVE_ASPECT letterboxes a square tile in an oblong cell". */
    private fun preserveAspectCheck(hidpi: Int) {
        val pixels =
            capture(16, 32, hidpi) {
                val source =
                    TileSheetGlyphSource(
                        sheet(),
                        columns = 2,
                        rows = 2,
                        cellWidthPx = 16,
                        cellHeightPx = 32,
                        scaling = TileScaling.PRESERVE_ASPECT,
                    )
                val window =
                    AsciiTileWindow.create {
                        glyphSource = source
                        widthInTiles = 1
                        heightInTiles = 1
                        fitToWindow = false
                    }
                try {
                    window.drawTile(0, 0, StaticAsciiTile(Char(0), Color.WHITE, Color.BLACK))
                    window.render()
                } finally {
                    window.dispose()
                }
            }
        shots += "letterbox" to pixels
        val band = pixels.averageColor(0, 12 * hidpi, 16 * hidpi, 20 * hidpi)
        val top = pixels.averageColor(0, 0, 16 * hidpi, 4 * hidpi)
        val bottom = pixels.averageColor(0, 28 * hidpi, 16 * hidpi, 32 * hidpi)
        report(
            checks[index].name,
            near(band.r, 1f) && near(top.r, 0f) && near(bottom.r, 0f),
            "band=${band.r} top=${top.r} bottom=${bottom.r}",
        )
    }

    /** Mirrors "prepareForCellSize re-downscales the sheet at the new size". */
    private fun resizeCheck(hidpi: Int) {
        var before = -1 to -1
        var after = -1 to -1
        var regionAfter = -1 to -1
        var noOp = false
        capture(1, 1, hidpi) {
            val source = TileSheetGlyphSource(sheet(), columns = 2, rows = 2, 16, 16)
            try {
                before = source.charWidthPx to source.charHeightPx
                val atlasBefore = source.glyph(Char(0))?.texture
                source.prepareForCellSize(16, 16)
                noOp = source.glyph(Char(0))?.texture === atlasBefore
                source.prepareForCellSize(24, 28)
                after = source.charWidthPx to source.charHeightPx
                val region = source.glyph(Char(0))
                regionAfter = (region?.regionWidth ?: -1) to (region?.regionHeight ?: -1)
            } finally {
                source.dispose()
            }
        }.dispose()
        report(
            checks[index].name,
            before == (16 to 16) && noOp && after == (24 to 28) && regionAfter == (24 to 28),
            "before=$before noOp=$noOp after=$after region=$regionAfter",
        )
    }

    /** Mirrors "a sheet that does not divide into whole tiles is rejected". */
    private fun rejectCheck(hidpi: Int) {
        var message: String? = null
        capture(1, 1, hidpi) {
            val attempt = runCatching { TileSheetGlyphSource(sheet(), columns = 5, rows = 2, 16, 16) }
            // Construction is supposed to fail. If validation ever regresses and it succeeds, the
            // instance owns a GL atlas texture — release it rather than leak it on the way to FAIL.
            attempt.getOrNull()?.dispose()
            message = attempt.exceptionOrNull()?.message
        }.dispose()
        report(
            checks[index].name,
            message == "sheet 128x128 does not divide into 5x2 whole tiles",
            "message=$message",
        )
    }

    /**
     * Mirrors "a magnified tile neither takes nor loses ink at its edges" (krogue-wcw). That spec's
     * `renderMagnifiedTile` blits ONE atlas region, magnified [BLEED_TILE_SCALE]×, into a
     * [BLEED_TILE_SPAN]-square capture FBO through a [BLEED_TILE_SPAN] ortho camera, and measures with the
     * shared [peakIn] / [averageColor] against the shared [BLEED_CLEAR_PEAK] / [BLEED_SOLID_MEAN].
     *
     * The one place this departs from [capture] is [hidpi]: it is deliberately **not** applied. Both sides
     * draw through a camera sized from the capture FBO, so the geometry never touches `Gdx.graphics` —
     * scaling the FBO alone would magnify by `BLEED_TILE_SCALE × hidpi` and read a different bleed weight
     * than CI does. The check still runs at the spec's window size (above) so the harness's own
     * geometry-parity rule holds.
     */
    private fun atlasGutterCheck(hidpi: Int) {
        val span = BLEED_TILE_SPAN

        fun magnified(slot: Int): Pixmap {
            val source = TileSheetGlyphSource(sheet(), columns = 2, rows = 2, BLEED_TILE_CELL, BLEED_TILE_CELL)
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
            val fbo = FrameBuffer(Pixmap.Format.RGBA8888, span, span, false)
            var begun = false
            try {
                fbo.begin()
                begun = true
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                val batch = SpriteBatch()
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
                    batch.dispose()
                }
                val raw = Pixmap.createFromFrameBuffer(0, 0, span, span)
                return try {
                    flipY(raw)
                } finally {
                    raw.dispose()
                }
            } finally {
                try {
                    if (begun) fbo.end()
                } finally {
                    fbo.dispose()
                    source.dispose()
                }
            }
        }

        // Slot 1 (the centred square, clear margin) sits directly right of slot 0 (full-bleed solid), so
        // ink in that margin is slot 0's, sampled across the seam.
        val sprite = magnified(1)
        val margin = peakIn(sprite, 0, 0, span / 4 - BLEED_TILE_SCALE, span)
        val third = span / 3
        val centre = sprite.averageColor(third, third, span - third, span - third).r.toDouble()
        shots += "gutter-sprite" to sprite
        report(checks[index].name, margin < BLEED_CLEAR_PEAK, "leftMarginPeak=$margin")
        report("wcw: the magnified tile's own square is solid", centre > BLEED_SOLID_MEAN, "centre=$centre")

        // Slot 0 is full-bleed: its right/bottom neighbours are empty, so an un-fixed sample past those
        // edges DIMS them (as a merely transparent gutter would). Its left/top edges are the page border,
        // where clamp-to-edge already did this job.
        val wall = magnified(0)
        val strips =
            mapOf(
                "left" to listOf(0, 0, BLEED_TILE_SCALE, span),
                "right" to listOf(span - BLEED_TILE_SCALE, 0, span, span),
                "top" to listOf(0, 0, span, BLEED_TILE_SCALE),
                "bottom" to listOf(0, span - BLEED_TILE_SCALE, span, span),
            )
        val means = strips.mapValues { (_, r) -> wall.averageColor(r[0], r[1], r[2], r[3]).r.toDouble() }
        shots += "gutter-wall" to wall
        means.forEach { (edge, mean) ->
            report("wcw: the full-bleed tile stays solid along its $edge edge", mean > BLEED_SOLID_MEAN, "mean=$mean")
        }
        println("  GUTTER hidpi=$hidpi sprite[margin=$margin centre=$centre]  wall$means")
    }

    // ── Harness plumbing (mirrors HeadlessGl.render) ───────────────────────────────────────────────

    /**
     * Renders [draw] into a capture FBO and reads it back upright — the same sequence [HeadlessGl.render]
     * performs, including clearing framebuffer 0 first (composite-cache `end()` calls rebind it) and
     * capturing from a framebuffer whose handle is **not** 0.
     */
    private fun capture(
        width: Int,
        height: Int,
        hidpi: Int,
        draw: () -> Unit,
    ): Pixmap {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, width * hidpi, height * hidpi, false)
        // render() catches per check and moves on, so a throwing draw() must not leave this framebuffer
        // bound: every later check would then capture into it and read the wrong pixels.
        var begun = false
        try {
            fbo.begin()
            begun = true
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            draw()
            val raw = Pixmap.createFromFrameBuffer(0, 0, width * hidpi, height * hidpi)
            return try {
                flipY(raw)
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
    }

    private fun flipY(src: Pixmap): Pixmap {
        val dst = Pixmap(src.width, src.height, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        // The caller owns dst on success; if a row copy throws, nobody does, so free it here.
        try {
            for (y in 0 until src.height) {
                dst.drawPixmap(src, 0, y, 0, src.height - 1 - y, src.width, 1)
            }
        } catch (t: Throwable) {
            dst.dispose()
            throw t
        }
        return dst
    }

    /**
     * The same synthetic sheet the spec writes: a 2x2 grid of 64px master tiles — slot 0 a full-bleed
     * solid, slot 1 a centred square with a margin, slot 2 empty, slot 3 solid blue. Written once.
     */
    private fun sheet(): FileHandle {
        sheet?.let { return it }
        val pixmap =
            Pixmap(128, 128, Pixmap.Format.RGBA8888).apply {
                blending = Pixmap.Blending.None
                setColor(0f, 0f, 0f, 0f)
                fill()
                setColor(Color.WHITE)
                fillRectangle(0, 0, 64, 64)
                fillRectangle(64 + 16, 16, 32, 32)
                setColor(Color.BLUE)
                fillRectangle(64, 64, 64, 64)
            }
        val temp = File.createTempFile("kotile-tilesheet", ".png").apply { deleteOnExit() }
        val file = Gdx.files.absolute(temp.absolutePath)
        try {
            PixmapIO.writePNG(file, pixmap)
        } finally {
            pixmap.dispose()
        }
        return file.also { sheet = it }
    }

    /** Writes each check's capture next to the configured output path, for eyeballing. */
    private fun dumpShots() {
        val out = System.getProperty("kotile.tsverify.out")
        try {
            val base = out?.let { File(it) } ?: return // nowhere to write; the finally still frees them
            base.parentFile?.mkdirs()
            shots.forEach { (name, pixmap) ->
                val file = File(base.parentFile, "${base.nameWithoutExtension}-$name.png")
                PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
                println("TSVERIFY wrote $file")
            }
        } finally {
            shots.forEach { (_, pixmap) -> pixmap.dispose() }
            shots.clear()
        }
    }

    private fun near(
        actual: Float,
        expected: Float,
    ): Boolean = abs(actual - expected) <= 0.05f

    private fun report(
        name: String,
        passed: Boolean,
        detail: String,
    ) {
        if (!passed) failures++
        println("TSVERIFY ${if (passed) "PASS" else "FAIL"}: $name -- $detail")
    }
}

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setWindowedMode(64, 16)
            setInitialVisible(false)
            disableAudio(true)
            setIdleFPS(60)
        }
    val harness = TileSheetManualVerify()
    Lwjgl3Application(harness, config)
    // Gdx.app.exit() alone returns normally, so the Gradle task went green no matter what the checks
    // said — a harness that reports FAIL and still exits 0 is worse than no harness, because the build
    // reads as clean. Exit non-zero on any failure, and on an early exit that never ran every check.
    if (!harness.completed) {
        println("TSVERIFY: INCOMPLETE -- the run ended before every check reported")
        exitProcess(1)
    }
    if (harness.failures > 0) exitProcess(1)
}
