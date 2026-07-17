package com.sletmoe.korogue.perception

import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.PipelineStage
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.pipeline.StandardStage
import com.sletmoe.korogue.world.GameWorld

/**
 * Caches each observer's [Perceived] once per tick (ADR-0015), the per-observer analogue of
 * [com.sletmoe.korogue.systems.LightingSystem] rewriting `Zone.lightMap`: perception only changes on
 * a tick, but the renderer and tick-by-tick AI sample it ~60×/sec, so computing it once per tick and
 * storing it pays off.
 *
 * **Opt in by carrying a `Perceived`.** Every entity that has a [Perceived] component gets a fresh one
 * written via [model]; the host attaches an empty `Perceived` to the entities it samples each frame
 * (the rendered unit; AI that checks every tick). The [PerceptionModel.perceive] query stays the model
 * — observers sampled less often just call it directly and skip the cache.
 *
 * Like `LightingSystem`, it only refreshes observers in a simulated zone; a dormant zone's observers
 * keep their last (frozen) `Perceived` (ADR-0008), and the scope is [gameWorld]'s
 * `SimulatedZonePolicy` rather than a per-system knob.
 *
 * Registers in [StandardStage.PERCEPTION], which must run after [StandardStage.LIGHTING]: the `Sight`
 * sense reveals only lit cells, so this has to read the light map the same tick produced. That
 * constraint is enforced — see [com.sletmoe.korogue.ecs.World.validateSystemOrder].
 */
class PerceptionSystem(
    private val gameWorld: GameWorld,
    private val model: PerceptionModel,
) : Staged {
    override val stage: PipelineStage get() = StandardStage.PERCEPTION

    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val active = gameWorld.simulatedZones()
        for (entity in world.entitiesWith<Perceived>()) {
            if (entity.get<ZoneMember>()?.zoneId !in active) continue
            world.set(entity.id, model.perceive(entity, gameWorld))
        }
    }
}
