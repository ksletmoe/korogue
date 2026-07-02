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
 * Sends **any** entity standing on a [Portal] through it: relocates the entity to the portal's
 * target (zone + position) atomically via [GameWorld.relocate] (ADR-0021 Mechanic B — required
 * so a chasing monster actually follows through the portal), and publishes a [ZoneChanged] event
 * per crossing entity (Phase 4c). Runs after `MovementSystem` (an entity must have stepped onto
 * the portal) and before `LightingSystem` (so the arriving zone is lit the same tick).
 *
 * Only the **player's** crossing switches [GameWorld.currentZoneId] — the active (simulated and
 * rendered) zone follows the player alone (ADR-0008); a monster stepping through a portal moves
 * zones without dragging the camera/simulation focus with it.
 *
 * This is deliberately generic: a portal transitions whoever stands on it, player or monster —
 * that's what makes cross-zone pursuit possible (krogue-s67.4). A game that doesn't want
 * monsters using portals/stairs simply keeps its AI from pathing onto them; the engine doesn't
 * special-case it.
 */
class PortalSystem(
    private val gameWorld: GameWorld,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val portals = world.entitiesWith<Portal, Position, ZoneMember>().toList()
        if (portals.isEmpty()) return

        // Portal fixtures themselves are excluded: a Portal has a Position + ZoneMember (its own
        // cell) but is terrain, not a mover — without this it would find itself "standing on"
        // its own portal and relocate itself every tick.
        world.entitiesWith<Position, ZoneMember>().toList().filterNot { it.has<Portal>() }.forEach { entity ->
            val pos = entity.require<Position>()
            val zoneId = entity.require<ZoneMember>().zoneId

            val portal =
                portals
                    .firstOrNull {
                        it.require<ZoneMember>().zoneId == zoneId &&
                            it.require<Position>().let { p -> p.x == pos.x && p.y == pos.y }
                    }?.require<Portal>() ?: return@forEach

            gameWorld.relocate(entity.id, portal.targetZoneId, portal.targetX, portal.targetY)
            if (entity.has<Player>()) {
                gameWorld.currentZoneId = portal.targetZoneId
            }
            world.events.publish(ZoneChanged(entity.id, from = zoneId, to = portal.targetZoneId))
        }
    }
}
