package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.Inventory
import com.sletmoe.korogue.components.Item
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.PipelineStage
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.ItemPickedUp
import com.sletmoe.korogue.pipeline.StandardStage

/**
 * Picks up [Item] entities the player is standing on: each is despawned, its name appended to the
 * player's [Inventory], and an [ItemPickedUp] event published (consumed by the log). Items are
 * non-blocking (the player walks onto them — see `MovementSystem`), so this runs after movement.
 * No-op unless the player has an [Inventory] component.
 */
class PickupSystem : Staged {
    override val stage: PipelineStage get() = StandardStage.PICKUP

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
