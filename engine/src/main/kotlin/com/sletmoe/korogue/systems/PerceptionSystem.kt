package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.perception.PerceptionModel
import com.sletmoe.korogue.world.GameWorld

/**
 * Caches each observer's [Perceived] once per tick (ADR-0015), the per-observer analogue of
 * [LightingSystem] rewriting `Zone.lightMap`: perception only changes on a tick, but the renderer and
 * tick-by-tick AI sample it ~60×/sec, so computing it once per tick and storing it pays off.
 *
 * **Opt in by carrying a `Perceived`.** Every entity that has a [Perceived] component gets a fresh one
 * written via [model]; the host attaches an empty `Perceived` to the entities it samples each frame
 * (the rendered unit; AI that checks every tick). The [PerceptionModel.perceive] query stays the model
 * — observers sampled less often just call it directly and skip the cache.
 *
 * Like `LightingSystem`, it only refreshes observers in an active zone ([activeZones]); a dormant
 * zone's observers keep their last (frozen) `Perceived` (ADR-0008).
 */
class PerceptionSystem(
    private val gameWorld: GameWorld,
    private val model: PerceptionModel,
    private val activeZones: (() -> Set<String>)? = null,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val active = activeZones?.invoke()
        for (entity in world.entitiesWith<Perceived>()) {
            if (active != null && entity.get<ZoneMember>()?.zoneId !in active) continue
            world.set(entity.id, model.perceive(entity, gameWorld))
        }
    }
}
