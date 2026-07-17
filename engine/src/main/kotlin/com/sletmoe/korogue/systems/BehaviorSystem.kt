package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.PipelineStage
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.pipeline.StandardStage
import com.sletmoe.korogue.registry.Registry
import com.sletmoe.korogue.world.GameWorld

/**
 * Runs each [Behavior] entity's strategy and emits the resulting [com.sletmoe.korogue.components.MoveIntent].
 * Registered before `MovementSystem` so AI intents are resolved in the same tick as the
 * player's. [strategies] is the `GameModule` registry the entity's `strategyId` resolves through;
 * tests inject a registry of deterministic stand-ins.
 *
 * Zone-scoped: only entities in [gameWorld]'s simulated zones act, so dormant zones freeze
 * (ADR-0008/ADR-0021). The scope comes from the world's `SimulatedZonePolicy` rather than a
 * per-system knob — see [com.sletmoe.korogue.world.SimulatedZonePolicy] to widen it.
 *
 * Skips any entity that already carries an [AttackIntent] — [BehaviorStrategy.decide] only ever
 * returns a move, so it has no way to know a system registered earlier this tick (`RangedAttackSystem`,
 * krogue-4tn) already committed the entity to an attack; without this guard it would also move,
 * taking two actions in one tick.
 */
class BehaviorSystem(
    private val gameWorld: GameWorld,
    private val strategies: Registry<BehaviorStrategy>,
) : Staged {
    override val stage: PipelineStage get() = StandardStage.BEHAVIOR

    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val active = gameWorld.simulatedZones()
        for (entity in world.entitiesWith<Behavior, Position, ZoneMember>().toList()) {
            if (entity.require<ZoneMember>().zoneId !in active) continue
            if (entity.has<AttackIntent>()) continue
            val move = strategies.resolve(entity.require<Behavior>().strategyId).decide(world, entity, ctx)
            if (move != null) world.set(entity.id, move)
        }
    }
}
