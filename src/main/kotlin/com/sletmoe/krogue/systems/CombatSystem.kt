package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.AttackIntent
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.ecs.System
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World

/**
 * Resolves [AttackIntent]s into damage, then clears the dead. Each attacker's intent is
 * consumed and applied to the target's [Health]; afterwards any non-player entity at 0
 * HP is despawned. The player is spared here — death/game-over handling is out of scope
 * (tracked separately as krogue-4zi).
 */
class CombatSystem(
    private val damage: Int = DEFAULT_DAMAGE,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        for (attacker in world.entitiesWith<AttackIntent>().toList()) {
            val targetId = world.remove<AttackIntent>(attacker.id)?.targetId ?: continue
            world.update<Health>(targetId) { it.copy(current = (it.current - damage).coerceAtLeast(0)) }
        }

        world
            .entitiesWith<Health>()
            .filter { it.require<Health>().dead && !it.has<Player>() }
            .toList()
            .forEach { world.despawn(it.id) }
    }

    private companion object {
        const val DEFAULT_DAMAGE = 20
    }
}
