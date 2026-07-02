package com.sletmoe.korogue.algorithms.zonegen

/**
 * The entity-aware world-generation seam (krogue-b1p, ADR-0019): paints terrain **and** places
 * entities by operating on a [ZoneGenContext]. It generalizes the terrain-only
 * [ZoneFeatureGenerator] — a generator that only mutates tiles can still be added via the
 * terrain-only [com.sletmoe.korogue.world.Zone.Builder.addFeature] overload, while one that also
 * needs to place monsters/items/gold/traps implements this interface and calls
 * [ZoneGenContext.spawn].
 *
 * Registered by id through the `GameModule.generators` registry (krogue-b1p.4) and composed with
 * the prefab/room primitive (krogue-b1p.2) and placement framework (krogue-b1p.3).
 */
fun interface ZoneGenerator {
    /** Paints terrain into and/or buffers entity spawns onto [ctx]. */
    fun generate(ctx: ZoneGenContext)
}
