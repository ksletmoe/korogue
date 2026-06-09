package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.Inventory
import com.sletmoe.krogue.components.Item
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.System
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World
import com.sletmoe.krogue.events.ItemPickedUp

/**
 * Picks up [Item] entities the player is standing on: each is despawned, its name appended to the
 * player's [Inventory], and an [ItemPickedUp] event published (consumed by the log). Items are
 * non-blocking (the player walks onto them — see `MovementSystem`), so this runs after movement.
 * No-op unless the player has an [Inventory] component.
 */
class PickupSystem : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val player = world.entitiesWith<Player, Position, ZoneMember>().firstOrNull() ?: return
        if (!player.has<Inventory>()) return
        val pos = player.require<Position>()
        val zoneId = player.require<ZoneMember>().zoneId

        val here =
            world
                .entitiesWith<Item, Position, ZoneMember>()
                .filter { it.require<ZoneMember>().zoneId == zoneId && it.require<Position>() == pos }
                .toList()

        for (item in here) {
            val name = item.require<Item>().name
            world.update<Inventory>(player.id) { it.copy(items = it.items + name) }
            world.despawn(item.id)
            world.events.publish(ItemPickedUp(name))
        }
    }
}
