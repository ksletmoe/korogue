package com.sletmoe.korogue.world

import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
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
     * The zones simulated each tick — the seam for ADR-0008's active-only model. Defaults
     * to just the current zone; widen it (e.g. current + adjacent) to let entities act
     * across transitions without a redesign (see krogue-s67). Zone-scoped systems read this.
     */
    open fun simulatedZones(): Set<String> = setOf(currentZoneId)

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

        fun zone(
            zoneId: String,
            width: Int,
            height: Int,
            isCurrentZone: Boolean = false,
            random: Random = Random.Default,
            zoneBuilderInit: Zone.Builder.() -> Unit = {},
        ): Zone {
            zones[zoneId] = Zone.create(zoneId, width, height, random, zoneBuilderInit)

            if (isCurrentZone) {
                currentZoneId = zoneId
            }

            return zones[zoneId]!!
        }

        fun build(): GameWorld {
            if (zones.isEmpty()) {
                throw RuntimeException("A World must have at least one Zone")
            }

            if (currentZoneId == null) {
                throw RuntimeException("A World must have the currentZoneId set")
            }

            return GameWorld(World(), zones, currentZoneId!!)
        }
    }

    companion object {
        fun create(worldBuilderInit: Builder.() -> Unit = {}): GameWorld {
            return initialize(Builder(), worldBuilderInit).build()
        }
    }
}
