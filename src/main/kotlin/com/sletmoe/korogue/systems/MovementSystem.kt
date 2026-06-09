package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Item
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.world.Zone

/**
 * Resolves [MoveIntent]s into actual movement. For each entity with a [MoveIntent] +
 * [Position] + [ZoneMember], the intent is consumed and one of three things happens:
 * a step onto walkable, unoccupied terrain (updates [Position]); a bump into another
 * entity in the destination cell (emits an [AttackIntent] for `CombatSystem`); or a
 * no-op into a wall.
 *
 * Resolution is sequential over a snapshot, so earlier movers' new positions are seen
 * by later movers within the same tick.
 */
class MovementSystem(
    private val zones: Map<String, Zone>,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        for (entity in world.entitiesWith<MoveIntent, Position, ZoneMember>().toList()) {
            val intent = world.remove<MoveIntent>(entity.id) ?: continue
            val pos = entity.require<Position>()
            val zoneId = entity.require<ZoneMember>().zoneId
            if (intent.dx == 0 && intent.dy == 0) continue

            val destX = pos.x + intent.dx
            val destY = pos.y + intent.dy
            val occupant = occupantAt(world, zoneId, destX, destY, except = entity.id)

            when {
                occupant != null -> world.set(entity.id, AttackIntent(occupant.id))
                zones[zoneId]?.isWalkable(destX, destY) == true -> world.set(entity.id, Position(destX, destY))
                // else: blocked by terrain — intent already consumed, no move.
            }
        }
    }

    private fun occupantAt(
        world: World,
        zoneId: String,
        x: Int,
        y: Int,
        except: com.sletmoe.korogue.ecs.EntityId,
    ) = world.entitiesWith<Position, ZoneMember>().firstOrNull {
        it.id != except &&
            !it.has<Portal>() && // portals are non-blocking — step onto them
            !it.has<Item>() && // items are non-blocking — step onto them to pick them up
            it.require<ZoneMember>().zoneId == zoneId &&
            it.require<Position>().let { p -> p.x == x && p.y == y }
    }
}
