package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.ZoneChanged
import com.sletmoe.korogue.world.GameWorld

/**
 * Sends the player through a [Portal] it is standing on: moves the player to the portal's
 * target (zone + position) and switches [GameWorld.currentZoneId] so the active —
 * simulated and rendered — zone follows the player (ADR-0008), and publishes a [ZoneChanged]
 * event (Phase 4c). Runs after `MovementSystem`
 * (the player must have stepped onto the portal) and before `LightingSystem` (so the
 * arriving zone is lit the same tick). Only the player transitions for now; dormant-zone
 * occupants stay put.
 */
class PortalSystem(
    private val gameWorld: GameWorld,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val player = world.entitiesWith<Player, Position, ZoneMember>().firstOrNull() ?: return
        val pos = player.require<Position>()
        val zoneId = player.require<ZoneMember>().zoneId

        val portal =
            world
                .entitiesWith<Portal, Position, ZoneMember>()
                .firstOrNull {
                    it.require<ZoneMember>().zoneId == zoneId &&
                        it.require<Position>().let { p -> p.x == pos.x && p.y == pos.y }
                }?.require<Portal>() ?: return

        world.set(player.id, ZoneMember(portal.targetZoneId))
        world.set(player.id, Position(portal.targetX, portal.targetY))
        gameWorld.currentZoneId = portal.targetZoneId
        world.events.publish(ZoneChanged(player.id, from = zoneId, to = portal.targetZoneId))
    }
}
