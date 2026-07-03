package com.sletmoe.korogue.world

/**
 * Which zones tick each frame (ADR-0021, Knob 1) — a pluggable policy per ADR-0014,
 * so a consumer can pick *whatever* set of zones it wants simulated; the engine
 * imposes no cap. [GameWorld.simulatedZones] delegates here and is called live each
 * tick (see zone-scoped systems' `activeZones` seam), so the returned set must track
 * current state rather than be cached across calls — under [CurrentPlusAdjacent], a
 * zone transition changes [GameWorld.currentZoneId] and the next call recomputes the
 * set automatically.
 *
 * Deliberately orthogonal to cross-zone *awareness* (ADR-0021, Knob 2): simulating a
 * zone makes its monsters act, it does not imply they perceive or target the player
 * elsewhere — that's a separate AI-side policy.
 */
fun interface SimulatedZonePolicy {
    fun simulatedZones(world: GameWorld): Set<String>
}

/** The default [SimulatedZonePolicy]: only [GameWorld.currentZoneId] ticks (today's behaviour). */
object CurrentZoneOnly : SimulatedZonePolicy {
    override fun simulatedZones(world: GameWorld): Set<String> = setOf(world.currentZoneId)
}

/**
 * Current zone plus its immediate neighbours per [adjacency] — the driving use case
 * (krogue-s67): a monster chasing the player keeps ticking after a zone transition
 * instead of freezing. N-hop reach is [adjacency]'s concern, not this policy's.
 */
class CurrentPlusAdjacent(
    private val adjacency: ZoneAdjacency,
) : SimulatedZonePolicy {
    override fun simulatedZones(world: GameWorld): Set<String> =
        setOf(world.currentZoneId) + adjacency.neighborsOf(world.currentZoneId)
}
