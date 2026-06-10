package com.sletmoe.kotile.utilities

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import org.junit.jupiter.api.Assumptions.assumeTrue

// ---------------------------------------------------------------------------
// File-level helpers (Kotlin forbids local enum/data inside a lambda body)
// ---------------------------------------------------------------------------

private enum class BenchPopMode { FULL, SPARSE_25 }

private data class BenchScenario(
    val label: String,
    val width: Int,
    val height: Int,
    val layers: Int,
    val pop: BenchPopMode,
)

private data class BenchCell(val id: Int)

/**
 * Microbenchmark for [LayeredTilemap.topCellAt] — the CPU composite path that
 * the render loop calls once per visible cell per frame.
 *
 * ## How to run
 *
 * Tests in this spec are **skipped by default** (via JUnit 5 assumption) unless
 * the system property `kotile.benchmark` is set to `true`. To run:
 *
 * ```
 * ./gradlew :library:test \
 *     --tests "com.sletmoe.kotile.utilities.LayeredTilemapBenchmark" \
 *     -Dkotile.benchmark=true
 * ```
 *
 * Without `-Dkotile.benchmark=true` the tests appear as "skipped/aborted" in
 * the report and never add measurable time to the regular build.
 *
 * ## Methodology
 *
 * Each scenario builds a [LayeredTilemap] at a fixed size and layer count,
 * populates cells (fully or sparsely), then times [MEASURE_ITERATIONS]
 * iterations of iterating every visible cell and calling `topCellAt(x, y)`.
 * The first [WARMUP_ITERATIONS] iterations are discarded to allow JIT
 * compilation. Wall-clock time (System.nanoTime) is used; no GL context is
 * needed. The harness is intentionally lightweight and is suitable for an
 * order-of-magnitude go/no-go decision, not sub-nanosecond precision.
 *
 * **Realistic krogue scale**: visible window 80×30 (~2 400 cells) to 120×50
 * (~6 000 cells); 1–5 z-layers; turn-based at 60 fps → 16.6 ms frame budget.
 */
class LayeredTilemapBenchmark : FunSpec({

    val WARMUP_ITERATIONS  = 500
    val MEASURE_ITERATIONS = 2_000

    // Soft assertion: composite path must stay well under 10 % of a 60-fps
    // frame budget (1.66 ms = 1 660 000 ns) at the largest tested scenario.
    val BUDGET_NS = 1_660_000L

    val scenarios = listOf(
        BenchScenario("80x30 / 1 layer / full",      80,  30, 1, BenchPopMode.FULL),
        BenchScenario("80x30 / 3 layers / full",     80,  30, 3, BenchPopMode.FULL),
        BenchScenario("80x30 / 5 layers / full",     80,  30, 5, BenchPopMode.FULL),
        BenchScenario("80x30 / 5 layers / sparse",   80,  30, 5, BenchPopMode.SPARSE_25),
        BenchScenario("120x50 / 1 layer / full",    120,  50, 1, BenchPopMode.FULL),
        BenchScenario("120x50 / 3 layers / full",   120,  50, 3, BenchPopMode.FULL),
        BenchScenario("120x50 / 5 layers / full",   120,  50, 5, BenchPopMode.FULL),
        BenchScenario("120x50 / 5 layers / sparse", 120,  50, 5, BenchPopMode.SPARSE_25),
    )

    val sentinel = BenchCell(42)

    fun buildMap(s: BenchScenario): LayeredTilemap<BenchCell> {
        val map = LayeredTilemap<BenchCell>(s.width, s.height)
        for (z in 0 until s.layers) {
            for (y in 0 until s.height) {
                for (x in 0 until s.width) {
                    when (s.pop) {
                        BenchPopMode.FULL -> map.setCell(x, y, z, sentinel)
                        BenchPopMode.SPARSE_25 -> {
                            // Only the top layer at ~25 % density; lower layers
                            // empty, forcing full-stack traversal on every miss.
                            if (z == s.layers - 1 && (x + y) % 4 == 0) {
                                map.setCell(x, y, z, sentinel)
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    fun scanOnce(map: LayeredTilemap<BenchCell>, width: Int, height: Int): Int {
        var hits = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (map.topCellAt(x, y) != null) hits++
            }
        }
        return hits
    }

    scenarios.forEach { scenario ->
        test("benchmark: ${scenario.label}") {
            // Skip unless explicitly opted-in; this keeps normal CI fast.
            assumeTrue(
                System.getProperty("kotile.benchmark") == "true",
                "Skipped: set -Dkotile.benchmark=true to run benchmarks"
            )

            val map = buildMap(scenario)
            var dummy = 0

            // Warmup: allow JIT to compile the hot path before timing
            repeat(WARMUP_ITERATIONS) { dummy += scanOnce(map, scenario.width, scenario.height) }

            // Timed measurement
            val startNs = System.nanoTime()
            repeat(MEASURE_ITERATIONS) { dummy += scanOnce(map, scenario.width, scenario.height) }
            val elapsedNs = System.nanoTime() - startNs

            val perFrameNs     = elapsedNs / MEASURE_ITERATIONS
            val budgetFraction = perFrameNs * 100.0 / 16_600_000.0

            println(
                "BENCH [${scenario.label}]  " +
                "cells=${scenario.width * scenario.height}  " +
                "layers=${scenario.layers}  " +
                "pop=${scenario.pop}  " +
                "per-frame=${perFrameNs} ns  " +
                "(%.3f%% of 16.6 ms)  [dummy=$dummy]".format(budgetFraction)
            )

            // Guard: composite path must consume < 10 % of a 60-fps frame budget.
            perFrameNs shouldBeLessThan BUDGET_NS
        }
    }
})
