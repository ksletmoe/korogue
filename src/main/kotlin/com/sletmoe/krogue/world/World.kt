package com.sletmoe.krogue.world

import com.sletmoe.krogue.utilities.initialize
import kotlin.random.Random

open class World(val zones: Map<String, Zone>, private var _currentZoneId: String) {
    var currentZoneId
        get() = _currentZoneId
        set(value) {
            if (value !in zones) {
                throw RuntimeException("Invalid zone ID '$value'. Does not exist in $zones")
            }
            _currentZoneId = value
        }

    val currentZone: Zone
        get() = zones[currentZoneId]!!

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

        fun build(): World {
            if (zones.isEmpty()) {
                throw RuntimeException("A World must have at least one Zone")
            }

            if (currentZoneId == null) {
                throw RuntimeException("A World must have the currentZoneId set")
            }

            return World(zones, currentZoneId!!)
        }
    }

    companion object {
        fun create(worldBuilderInit: Builder.() -> Unit = {}): World {
            return initialize(Builder(), worldBuilderInit).build()
        }
    }
}
