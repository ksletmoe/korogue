package com.sletmoe.krogue.world

import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.Entity
import com.sletmoe.krogue.ecs.World
import com.sletmoe.krogue.utilities.initialize
import kotlin.random.Random

/**
 * The krogue game world: the ECS [World] (all entities + systems) composed with the
 * zone registry (terrain by id + the current zone). Per ADR-0007, `GameWorld`
 * *composes* `ecs.World` rather than merging, so the ECS core stays game-agnostic;
 * zones and tiles are krogue concepts that live here.
 *
 * Occupants (creatures, the player, light emitters) are ECS entities tagged with
 * [ZoneMember] + [Position] and owned by [ecs]; a [Zone] is terrain only. Query
 * occupancy via [entityAt] (4b-s3).
 */
open class GameWorld(
    val ecs: World,
    val zones: Map<String, Zone>,
    private var _currentZoneId: String,
) {
    var currentZoneId: String
        get() = _currentZoneId
        set(value) {
            if (value !in zones) {
                throw RuntimeException("Invalid zone ID '$value'. Does not exist in $zones")
            }
            _currentZoneId = value
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
