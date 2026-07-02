package com.sletmoe.korogue.world

import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.ZoneMember

/**
 * A zone's adjacent zones (ADR-0021, Mechanic A) — the seam `CurrentPlusAdjacent`
 * simulation-scope policies and cross-zone AI pathing build on. Pluggable per
 * ADR-0014: the engine ships [PortalZoneAdjacency] as the default, but a consumer
 * with non-portal adjacency (e.g. an overworld grid of neighbouring cells) can
 * supply its own.
 *
 * N-hop adjacency is the caller's concern — repeated application of [neighborsOf]
 * composes ("current + 2 hops" is just applying it twice) — this interface hardcodes
 * no distance.
 */
fun interface ZoneAdjacency {
    /** The zones directly adjacent to [zoneId]. Empty if [zoneId] has no neighbours. */
    fun neighborsOf(zoneId: String): Set<String>
}

/**
 * The default [ZoneAdjacency]: a zone's neighbours are the distinct [Portal.targetZoneId]s
 * of portal entities standing in that zone — i.e. wherever a [Portal] leads, that zone
 * counts as adjacent. Reads [gameWorld] live, so it tracks portals added or removed after
 * construction (no snapshot is cached).
 */
class PortalZoneAdjacency(
    private val gameWorld: GameWorld,
) : ZoneAdjacency {
    override fun neighborsOf(zoneId: String): Set<String> =
        gameWorld.ecs
            .entitiesWith<Portal, ZoneMember>()
            .filter { it.require<ZoneMember>().zoneId == zoneId }
            .map { it.require<Portal>().targetZoneId }
            .toSet()
}
