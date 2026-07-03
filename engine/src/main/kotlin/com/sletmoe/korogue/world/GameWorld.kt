package com.sletmoe.korogue.world

import com.sletmoe.korogue.algorithms.zonegen.SpawnRequest
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.utilities.initialize
import kotlin.random.Random

/**
 * The korogue game world: the ECS [World] (all entities + systems) composed with the
 * zone registry (terrain by id + the current zone). Per ADR-0007, `GameWorld`
 * *composes* `ecs.World` rather than merging, so the ECS core stays game-agnostic;
 * zones and tiles are korogue concepts that live here.
 *
 * Occupants (creatures, the player, light emitters) are ECS entities tagged with
 * [ZoneMember] + [Position] and owned by [ecs]; a [Zone] is terrain only. Query
 * occupancy via [entityAt] (4b-s3).
 *
 * The zone registry is **mutable**: zones can be [addZone]ed and [removeZone]d after
 * construction, so a world can grow or regenerate its map over a session — e.g. a
 * roguelike generating each dungeon level on descent (ADR-0016, krogue-go8). The
 * exposed [zones] map is a live, read-only view of that registry, so zone-scoped
 * systems (lighting, perception, movement) and the renderer that captured it at wiring
 * time observe additions and removals without being rebuilt.
 */
open class GameWorld(
    val ecs: World,
    zones: Map<String, Zone>,
    private var _currentZoneId: String,
) {
    private val zoneRegistry: MutableMap<String, Zone> = zones.toMutableMap()

    /** The zone registry as a live, read-only view — reflects later [addZone]/[removeZone] calls. */
    val zones: Map<String, Zone> = zoneRegistry

    var currentZoneId: String
        get() = _currentZoneId
        set(value) {
            if (value !in zones) {
                throw RuntimeException("Invalid zone ID '$value'. Does not exist in $zones")
            }
            _currentZoneId = value
        }

    /**
     * Registers [zone] (replacing any existing zone with the same [Zone.zoneId]) so it can be
     * entered. Does not change [currentZoneId]; set that once the new zone is populated.
     */
    fun addZone(zone: Zone) {
        zoneRegistry[zone.zoneId] = zone
    }

    /**
     * Drops the zone [zoneId] from the registry — its terrain is discarded (an ephemeral level the
     * player has left, say). Occupant entities tagged with that zone are the ECS's concern and are
     * not touched here; despawn them first. The [currentZoneId] cannot be removed.
     */
    fun removeZone(zoneId: String) {
        if (zoneId == currentZoneId) {
            throw RuntimeException("Cannot remove the current zone '$zoneId'")
        }
        zoneRegistry.remove(zoneId)
    }

    val currentZone: Zone
        get() = zones[currentZoneId]!!

    /**
     * The policy deciding which zones are simulated (ADR-0021, Knob 1). Defaults to
     * [CurrentZoneOnly] (today's behaviour); swap in [CurrentPlusAdjacent] or a custom
     * [SimulatedZonePolicy] to widen scope (e.g. so entities keep acting across a zone
     * transition — see krogue-s67) without a redesign.
     */
    var simulatedZonePolicy: SimulatedZonePolicy = CurrentZoneOnly

    /**
     * The zones simulated each tick — the seam for ADR-0008's active-only model, delegating
     * to [simulatedZonePolicy]. Zone-scoped systems read this live, each tick.
     */
    open fun simulatedZones(): Set<String> = simulatedZonePolicy.simulatedZones(this)

    /**
     * Atomically moves entity [entityId] to ([x], [y]) in zone [zoneId] — sets its [ZoneMember]
     * and [Position] together, in one place, so no caller can observe (or leave behind) a
     * half-updated entity with a [ZoneMember] pointing at one zone and a [Position] meant for
     * another. The atomic primitive for any boundary-crossing move (ADR-0021 Mechanic B) — e.g.
     * [com.sletmoe.korogue.systems.PortalSystem] sending an entity through a [com.sletmoe.korogue.components.Portal].
     * No-op if [entityId] doesn't exist (delegates to [World.set], which is itself a no-op for
     * unknown ids).
     */
    fun relocate(
        entityId: EntityId,
        zoneId: String,
        x: Int,
        y: Int,
    ) {
        ecs.set(entityId, ZoneMember(zoneId))
        ecs.set(entityId, Position(x, y))
    }

    /** The entity occupying ([x], [y]) in zone [zoneId], or null. Occupancy lives in the ECS now. */
    fun entityAt(
        zoneId: String,
        x: Int,
        y: Int,
    ): Entity? =
        ecs.entitiesWith<Position, ZoneMember>().firstOrNull {
            val pos = it.require<Position>()
            pos.x == x && pos.y == y && it.require<ZoneMember>().zoneId == zoneId
        }

    /** True if ([x], [y]) in [zoneId] is walkable terrain *and* unoccupied. */
    fun isWalkable(
        zoneId: String,
        x: Int,
        y: Int,
    ): Boolean {
        val zone = zones[zoneId] ?: return false
        return zone.isWalkable(x, y) && entityAt(zoneId, x, y) == null
    }

    open class Builder {
        private val zones: MutableMap<String, Zone> = mutableMapOf()
        private var currentZoneId: String? = null

        // Entity spawns buffered by entity-aware zone generators (ADR-0019), paired with their
        // zone id. Materialized in build() once the ECS World exists — generation itself never
        // touches a live World.
        private val pendingSpawns: MutableList<Pair<String, SpawnRequest>> = mutableListOf()

        fun zone(
            zoneId: String,
            width: Int,
            height: Int,
            isCurrentZone: Boolean = false,
            random: Random = Random.Default,
            zoneBuilderInit: Zone.Builder.() -> Unit = {},
        ): Zone {
            // Build the Zone.Builder directly (rather than Zone.create) so its buffered
            // pendingSpawns can be captured and materialized in build().
            val zoneBuilder = initialize(Zone.Builder(zoneId, width, height, random), zoneBuilderInit)
            val zone = zoneBuilder.build()
            zones[zoneId] = zone
            zoneBuilder.pendingSpawns.forEach { pendingSpawns += zoneId to it }

            if (isCurrentZone) {
                currentZoneId = zoneId
            }

            return zone
        }

        fun build(): GameWorld {
            if (zones.isEmpty()) {
                throw RuntimeException("A World must have at least one Zone")
            }

            if (currentZoneId == null) {
                throw RuntimeException("A World must have the currentZoneId set")
            }

            val gameWorld = GameWorld(World(), zones, currentZoneId!!)

            // Materialize buffered zone-gen spawns now that the ECS world exists (ADR-0019),
            // adding Position (the cell) and ZoneMember (the zone) to each entity's own components.
            pendingSpawns.forEach { (zoneId, request) ->
                gameWorld.ecs.spawn(request.components + Position(request.x, request.y) + ZoneMember(zoneId))
            }

            return gameWorld
        }
    }

    companion object {
        fun create(worldBuilderInit: Builder.() -> Unit = {}): GameWorld {
            return initialize(Builder(), worldBuilderInit).build()
        }
    }
}
