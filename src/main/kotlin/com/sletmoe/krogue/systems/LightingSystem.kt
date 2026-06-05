package com.sletmoe.krogue.systems

import com.sletmoe.krogue.algorithms.color.ColorBlending
import com.sletmoe.krogue.algorithms.lighting.LightCalculators
import com.sletmoe.krogue.algorithms.lighting.LightValue
import com.sletmoe.krogue.components.LightEmitter
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.System
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World
import com.sletmoe.krogue.utilities.distance
import com.sletmoe.krogue.world.Zone

/**
 * Recomputes every [zone][zones]'s `lightMap` from the [LightEmitter] entities in it,
 * once per tick. Replaces the old `Zone.recalculateLightMap`: instead of a Zone owning
 * light sources, light is a query — each entity with [LightEmitter] + [Position] +
 * [ZoneMember] contributes to its zone's map, and overlapping sources blend (softLight
 * on colour, screen on intensity, matching the prior behaviour and its tests).
 */
class LightingSystem(
    private val zones: Map<String, Zone>,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        zones.values.forEach { it.lightMap.fill(null) }

        for (entity in world.entitiesWith<LightEmitter, Position, ZoneMember>()) {
            val zone = zones[entity.require<ZoneMember>().zoneId] ?: continue
            val emitter = entity.require<LightEmitter>()
            val origin = entity.require<Position>().point
            val calculator = LightCalculators.resolve(emitter.calculatorId)

            zone.lightMap.forEachCoordinateInRadius(origin, emitter.radius) { coord ->
                val existing = zone.lightMap[coord]
                val contribution = calculator.calculateLightValue(emitter.color, emitter.radius, origin.distance(coord))
                zone.lightMap[coord] =
                    if (existing != null) {
                        LightValue(
                            ColorBlending.softLight(existing.normalizedColor, contribution.normalizedColor),
                            ColorBlending.screen(existing.intensity, contribution.intensity),
                        )
                    } else {
                        contribution
                    }
            }
        }
    }
}
