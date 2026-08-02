package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.display.ascii.TileInk
import com.sletmoe.kotile.display.ascii.TileScaling
import com.sletmoe.kotile.display.ascii.TileSheetGlyphSource
import java.io.File
import kotlin.math.abs

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
    private class Check(
        val name: String,
        val width: Int,
        val height: Int,
        val run: (Int) -> Unit,
    )

    private var failures = 0
    private var index = 0
    private var settled = 0
    private var sheet: FileHandle? = null
    private val shots = mutableListOf<Pair<String, Pixmap>>()

    private val checks =
        listOf(
            Check("coverage sheet tints per cell, empty tile stays clear", 64, 16, ::coverageTintCheck),
            Check("FULL_COLOR carries the sheet's colour", 32, 16, ::fullColorCheck),
            Check("PRESERVE_ASPECT letterboxes a square tile", 16, 32, ::preserveAspectCheck),
            Check("prepareForCellSize re-downscales; same size is a no-op", 1, 1, ::resizeCheck),
            Check("a sheet that doesn't divide into whole tiles is rejected", 1, 1, ::rejectCheck),
        )

    override fun render() {
        if (index >= checks.size) {
            dumpShots()
            println(if (failures == 0) "TSVERIFY: ALL PASSED" else "TSVERIFY: $failures FAILED")
            Gdx.app.exit()
            return
        }
        val check = checks[index]
        // Resize and let the resize settle, so each check runs at its spec's window size (HeadlessGl
        // resizes the shared window the same way before every render).
        if (Gdx.graphics.width != check.width || Gdx.graphics.height != check.height) {
            Gdx.graphics.setWindowedMode(check.width, check.height)
            settled = 0
            return
        }
        if (settled++ < 2) return
        val hidpi = (Gdx.graphics.backBufferWidth / Gdx.graphics.width).coerceAtLeast(1)
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
                near(empty.r, 0f),
            "region=$regionSize master=$masterSize tiles=$tiles outOfRange=$outOfRange " +
                "inked=(${inked.r}, ${inked.g}, ${inked.b}) emptyR=${empty.r}",
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
            message =
                runCatching {
                    TileSheetGlyphSource(sheet(), columns = 5, rows = 2, 16, 16)
                }.exceptionOrNull()?.message
        }.dispose()
        report(
            checks[index].name,
            message == "sheet 128x128 does not divide into 5x2 whole tiles",
            "message=$message",
        )
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
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        draw()
        val raw = Pixmap.createFromFrameBuffer(0, 0, width * hidpi, height * hidpi)
        fbo.end()
        fbo.dispose()
        return try {
            flipY(raw)
        } finally {
            raw.dispose()
        }
    }

    private fun flipY(src: Pixmap): Pixmap {
        val dst = Pixmap(src.width, src.height, Pixmap.Format.RGBA8888).apply { blending = Pixmap.Blending.None }
        for (y in 0 until src.height) {
            dst.drawPixmap(src, 0, y, 0, src.height - 1 - y, src.width, 1)
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
        val file = Gdx.files.absolute(File.createTempFile("kotile-tilesheet", ".png").absolutePath)
        try {
            PixmapIO.writePNG(file, pixmap)
        } finally {
            pixmap.dispose()
        }
        return file.also { sheet = it }
    }

    /** Writes each check's capture next to the configured output path, for eyeballing. */
    private fun dumpShots() {
        val out = System.getProperty("kotile.tsverify.out") ?: return
        val base = File(out)
        base.parentFile?.mkdirs()
        shots.forEach { (name, pixmap) ->
            val file = File(base.parentFile, "${base.nameWithoutExtension}-$name.png")
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), pixmap)
            println("TSVERIFY wrote $file")
            pixmap.dispose()
        }
        shots.clear()
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
    Lwjgl3Application(TileSheetManualVerify(), config)
}
