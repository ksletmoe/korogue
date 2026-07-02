package com.sletmoe.korogue.algorithms.zonegen

import com.sletmoe.korogue.utilities.IntRect
import kotlin.random.Random

/**
 * Scatters prefabs across a [ZoneGenContext], the placement framework of ADR-0019 (krogue-b1p.3):
 * given a way to produce a prefab ([factory]) and constraints ([targetCount], [minSpacing],
 * [maxAttempts]), it repeatedly picks a random, in-bounds candidate origin, rejects it if it
 * collides with an already-placed prefab, and [Prefab.stamp]s it if not — tracking the occupied
 * [IntRect] of each placement so later attempts avoid it.
 *
 * [factory] rather than a single [Prefab] is what lets a caller scatter *variable*-size
 * structures (e.g. a "shack stamper" producing 2-5x2-5 rooms, ADR-0019's acceptance case): it is
 * called once per attempt with the placer's [Random] source, and its return value's `width` x
 * `height` is what gets bounds-fit and collision-checked for that attempt (see [Prefab]'s own
 * KDoc for why size range lives here, in a factory, rather than on [Prefab] itself).
 *
 * [scatter] never relies on [Prefab.stamp] to reject a bad origin — it computes an origin that is
 * already guaranteed to fit within [ZoneGenContext.tiles] and not collide before calling `stamp`,
 * so `stamp`'s own bounds check is a pure assertion, never a rejection path here. Candidate origins
 * are drawn uniformly at random from the fully-in-bounds range for each attempt's concrete prefab
 * size; there's no smarter placement heuristic (e.g. grid-snapping, blue-noise) — try-N plus
 * uniform sampling is the simplest thing that meets the "no overlap" acceptance bar, and keeps
 * `scatter` a small, easily-tested loop.
 *
 * "Density" is expressed by the caller picking [targetCount] (e.g. derived from zone area /
 * average prefab area) — the placer itself only knows "how many", not zone-area math.
 *
 * Both bounds-fit and spacing failures are attempts that consume the [maxAttempts] budget but
 * place nothing, so `scatter` degrades gracefully (fewer placements, never a throw) when the zone
 * is too small or the constraints are too tight — it gives up once [maxAttempts] is exhausted, even
 * if [targetCount] was never reached.
 *
 * ```kotlin
 * val shackPlacer = PrefabPlacer(targetCount = 6, minSpacing = 1)
 * shackPlacer.scatter(ctx) { random -> buildShack(random) } // buildShack: (Random) -> Prefab, 2-5x2-5
 * ```
 *
 * @property targetCount the number of prefabs to place; `scatter` stops early once this many have
 *   landed
 * @property minSpacing the minimum gap, in tiles, required between any two placements' bounding
 *   boxes (`0` allows them to touch but not overlap)
 * @property maxAttempts the total candidate-origin budget across the whole [scatter] call (not
 *   per-placement); defaults to [targetCount] `* DEFAULT_ATTEMPTS_PER_TARGET`, generous enough for
 *   sparse scattering without being unbounded
 */
class PrefabPlacer(
    val targetCount: Int,
    val minSpacing: Int = 0,
    val maxAttempts: Int = targetCount * DEFAULT_ATTEMPTS_PER_TARGET,
) {
    init {
        require(targetCount >= 0) { "targetCount must be >= 0, got $targetCount" }
        require(minSpacing >= 0) { "minSpacing must be >= 0, got $minSpacing" }
        require(maxAttempts >= 0) { "maxAttempts must be >= 0, got $maxAttempts" }
    }

    /**
     * Attempts to place up to [targetCount] prefabs (each produced by [factory]) into [ctx],
     * stamping every one that fits within [ZoneGenContext.tiles] and doesn't collide (respecting
     * [minSpacing]) with a prior placement from this call. Consumes [ctx]'s [ZoneGenContext.random]
     * for both prefab generation and candidate origins, so results are deterministic for a seeded
     * context.
     *
     * @return the occupied [IntRect] of each prefab actually stamped, in placement order; shorter
     *   than [targetCount] if [maxAttempts] ran out first
     */
    fun scatter(
        ctx: ZoneGenContext,
        factory: (Random) -> Prefab,
    ): List<IntRect> {
        val placed = mutableListOf<IntRect>()
        var attempts = 0

        while (placed.size < targetCount && attempts < maxAttempts) {
            attempts++

            val prefab = factory(ctx.random)
            if (prefab.width > ctx.tiles.width || prefab.height > ctx.tiles.height) continue

            val originX = ctx.random.nextInt(ctx.tiles.width - prefab.width + 1)
            val originY = ctx.random.nextInt(ctx.tiles.height - prefab.height + 1)
            val candidate = IntRect(originX, originY, prefab.width, prefab.height)
            if (placed.any { it.overlaps(candidate, minSpacing) }) continue

            prefab.stamp(ctx, originX, originY)
            placed += candidate
        }

        return placed
    }

    companion object {
        /** Default [maxAttempts] multiplier applied to [targetCount] when not given explicitly. */
        const val DEFAULT_ATTEMPTS_PER_TARGET = 20
    }
}
