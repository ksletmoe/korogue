package com.sletmoe.kotile

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.GlyphFit

/**
 * krogue-4jh: attributes the wall-clock of one `FreeTypeGlyphSource` rasterise to its stages.
 *
 * krogue-9x7.6 added the shift cache and measured what it buys: a 20px TEXT rasterise went 27 → 26ms,
 * so removing the search entirely leaves ~85-95% of a resize's cost in place. This harness answers
 * *where that time actually goes*, reading the per-stage nanos [FreeTypeGlyphSource.lastTiming]
 * records from inside `rasterize` (every stage there is private, so no outside timer can split them).
 *
 * **On GL timing.** GL commands queue asynchronously, so a naive timer bills the GPU's work to
 * whichever later call blocks on it — here the `glReadPixels` in the read-back, which would make the
 * render look free. Runs therefore set [FreeTypeGlyphSource.profileSyncGl], which drains the pipeline
 * at every stage boundary. That inflates the total (a stall per stage), so each configuration is also
 * timed **unsynced** — the honest end-to-end number — and both are printed. Trust the *unsynced total*
 * for "how slow is a resize" and the *synced split* for "which stage owns it".
 *
 * Not a benchmark harness: [REPEATS] runs after a warm-up, reported as a median, on one machine with
 * no isolation. It is meant to find the dominant stage, not to defend a percentage.
 *
 *   ./gradlew :kotile:library:freetypeProfile
 */
private const val REPEATS = 5
private val CELL_SIZES = listOf(20, 32, 48)

/** Window size is irrelevant to the measurement — the source rasterises into its own FBOs. */
private const val WIN = 64

private class FreeTypeProfile : ApplicationAdapter() {
    private var frame = 0

    override fun render() {
        // Frame 1 can still be mid-context-setup; measure from frame 2 like the other GL harnesses.
        if (++frame < 2) return

        println("FTPROFILE ${System.getProperty("os.name")} / ${System.getProperty("java.version")}")
        println("FTPROFILE gl=${Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER)}")
        warmUp()

        for (fit in listOf(GlyphFit.TEXT, GlyphFit.TILE)) {
            for (cell in CELL_SIZES) {
                profileOne(fit, cell)
            }
        }
        println("FTPROFILE done")
        Gdx.app.exit()
    }

    /**
     * Rasterises a few sizes through the same code path and throws the results away, so the JIT has
     * compiled the SAT/search/emit loops before anything is timed. Cold, these read several times slower
     * and would dominate the first measured size.
     */
    private fun warmUp() {
        val src = Fonts.cascadiaMono(16, 16, fit = GlyphFit.TEXT, snapToPixelGrid = true)
        for (c in listOf(18, 22, 26, 30)) src.prepareForCellSize(c, c)
        src.dispose()
        val tile = Fonts.cascadiaMono(16, 16, fit = GlyphFit.TILE, snapToPixelGrid = true)
        for (c in listOf(18, 22)) tile.prepareForCellSize(c, c)
        tile.dispose()
        println("FTPROFILE warmed up")
    }

    /**
     * Times [REPEATS] *searching* rasterises of a [cell]px cell under [fit], synced (per-stage) and
     * unsynced (end-to-end), and prints the median split.
     *
     * Every measured rasterise must be a genuine shift-cache miss, or it would silently time the cached
     * path: so each repeat builds a **fresh source at a scratch size** (`cell + 1`) and then moves it to
     * `cell`. Constructing at `cell` would rasterise — and cache — the size under test in the
     * constructor. The cached path is timed separately below for contrast.
     */
    private fun profileOne(
        fit: GlyphFit,
        cell: Int,
    ) {
        val synced = ArrayList<FreeTypeGlyphSource.RasterizeTiming>(REPEATS)
        val unsyncedTotals = ArrayList<Long>(REPEATS)
        var cachedTotal = 0L
        var supersample = 0

        repeat(REPEATS) {
            val src = Fonts.cascadiaMono(cell + 1, cell + 1, fit = fit, snapToPixelGrid = true)
            src.profileSyncGl = true
            src.prepareForCellSize(cell, cell)
            synced += copyOf(src.lastTiming)
            supersample = src.effectiveSupersample

            // Unsynced end-to-end for the SAME size: it is now a cache hit, which is a different amount of
            // work — so use a fresh source for the honest searching number.
            src.dispose()
            val plain = Fonts.cascadiaMono(cell + 1, cell + 1, fit = fit, snapToPixelGrid = true)
            plain.prepareForCellSize(cell, cell)
            unsyncedTotals += plain.lastTiming.totalNanos
            // Now a revisit of `cell` is a cache hit: the search-free cost of the same rasterise.
            plain.prepareForCellSize(cell + 1, cell + 1)
            plain.prepareForCellSize(cell, cell)
            cachedTotal = plain.lastTiming.totalNanos
            plain.dispose()
        }

        val ss = supersample
        val master = "${cell * 16 * ss}x${cell * 16 * ss}"
        val medianTotal = median(synced.map { it.totalNanos })
        println()
        println("FTPROFILE fit=$fit cell=${cell}px ss=$ss master=$master  (median of $REPEATS)")
        line("freetype generateFont", median(synced.map { it.fontGenNanos }), medianTotal)
        line("GL render of master", median(synced.map { it.renderNanos }), medianTotal)
        line("GPU gamma halving", median(synced.map { it.halveNanos }), medianTotal)
        line("readback (glReadPixels)", median(synced.map { it.readbackNanos }), medianTotal)
        line("flipY", median(synced.map { it.flipNanos }), medianTotal)
        line("CPU downsample + search", median(synced.map { it.downsampleNanos }), medianTotal)
        line("brightness curve", median(synced.map { it.brightnessNanos }), medianTotal)
        line("gutter repack", median(synced.map { it.padNanos }), medianTotal)
        line("atlas texture upload", median(synced.map { it.uploadNanos }), medianTotal)
        line("un-attributed glue", median(synced.map { unattributed(it) }), medianTotal)
        println("  %-26s %7.1fms  (synced; adds a stall per stage)".format("SYNCED TOTAL", medianTotal / 1e6))
        println("  %-26s %7.1fms  <- the real cost of a resize".format("UNSYNCED TOTAL", median(unsyncedTotals) / 1e6))
        println("  %-26s %7.1fms  (same size, shift cache hit)".format("cached rasterise", cachedTotal / 1e6))
    }

    private fun line(
        label: String,
        nanos: Long,
        totalNanos: Long,
    ) {
        val pct = if (totalNanos > 0) 100.0 * nanos / totalNanos else 0.0
        println("  %-26s %7.1fms  %4.1f%%".format(label, nanos / 1e6, pct))
    }

    /** Whatever the stage timers did not cover: FBO allocation, the region rebuild, the timing calls. */
    private fun unattributed(t: FreeTypeGlyphSource.RasterizeTiming): Long =
        (
            t.totalNanos - t.fontGenNanos - t.renderNanos - t.halveNanos - t.readbackNanos -
                t.flipNanos - t.downsampleNanos - t.brightnessNanos - t.padNanos - t.uploadNanos
        ).coerceAtLeast(0L)

    /**
     * Snapshots [t] — `lastTiming` is a single mutable instance the source overwrites on the next
     * rasterise, so a repeat loop that kept the reference would end up with N views of the last run.
     */
    private fun copyOf(t: FreeTypeGlyphSource.RasterizeTiming): FreeTypeGlyphSource.RasterizeTiming =
        FreeTypeGlyphSource.RasterizeTiming().also {
            it.fontGenNanos = t.fontGenNanos
            it.renderNanos = t.renderNanos
            it.halveNanos = t.halveNanos
            it.readbackNanos = t.readbackNanos
            it.flipNanos = t.flipNanos
            it.downsampleNanos = t.downsampleNanos
            it.brightnessNanos = t.brightnessNanos
            it.padNanos = t.padNanos
            it.uploadNanos = t.uploadNanos
            it.totalNanos = t.totalNanos
        }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}

fun main() {
    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile freetype profile")
            setWindowedMode(WIN, WIN)
            disableAudio(true)
            setInitialVisible(false)
        }
    Lwjgl3Application(FreeTypeProfile(), config)
}
