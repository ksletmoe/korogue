package com.sletmoe.korogue.systems

import com.sletmoe.korogue.algorithms.color.ColorBlending
import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.algorithms.lighting.LightValueCalculator
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.utilities.distance
import com.sletmoe.korogue.world.Zone

/**
 * Recomputes every [zone][zones]'s `lightMap` from the [LightEmitter] entities in it,
 * once per tick. Replaces the old `Zone.recalculateLightMap`: instead of a Zone owning
 * light sources, light is a query — each entity with [LightEmitter] + [Position] +
 * [ZoneMember] contributes to its zone's map, and overlapping sources blend (softLight
 * on colour, screen on intensity, matching the prior behaviour and its tests).
 *
 * [ambientLight] is the baseline every cell starts at before emitters are added (default `null` =
 * unlit, so only cells within an emitter's reach are visible). Pass [LightValue.FULLBRIGHT] for a
 * uniformly-lit zone ("global illumination") — the model for games that don't track light per-source
 * (e.g. Rogue), where map visibility is just line-of-sight; emitters, if any, still blend on top.
 */
class LightingSystem(
    private val zones: Map<String, Zone>,
    private val resolveCalculator: (String) -> LightValueCalculator,
    private val activeZones: (() -> Set<String>)? = null,
    private val ambientLight: LightValue? = null,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val active = activeZones?.invoke()
        // Only recompute active zones; dormant zones keep their last (frozen) lightMap.
        val target = if (active == null) zones else zones.filterKeys { it in active }
        target.values.forEach { it.lightMap.fill(ambientLight) }

        for (entity in world.entitiesWith<LightEmitter, Position, ZoneMember>()) {
            if (active != null && entity.require<ZoneMember>().zoneId !in active) continue
            val zone = target[entity.require<ZoneMember>().zoneId] ?: continue
            val emitter = entity.require<LightEmitter>()
            val origin = entity.require<Position>().point
            val calculator = resolveCalculator(emitter.calculatorId)

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
