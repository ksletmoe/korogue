package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.System
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World

/**
 * Runs each [Behavior] entity's strategy and emits the resulting [com.sletmoe.krogue.components.MoveIntent].
 * Registered before `MovementSystem` so AI intents are resolved in the same tick as the
 * player's. The [resolveStrategy] seam (defaulting to [BehaviorStrategies]) keeps the
 * system testable with deterministic stand-in strategies.
 */
class BehaviorSystem(
    private val resolveStrategy: (String) -> BehaviorStrategy = BehaviorStrategies::resolve,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        for (entity in world.entitiesWith<Behavior, Position, ZoneMember>().toList()) {
            val move = resolveStrategy(entity.require<Behavior>().strategyId).decide(world, entity, ctx)
            if (move != null) world.set(entity.id, move)
        }
    }
}
