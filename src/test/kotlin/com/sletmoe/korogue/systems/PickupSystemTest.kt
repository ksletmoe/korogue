package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.Inventory
import com.sletmoe.korogue.components.Item
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.ItemPickedUp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class PickupSystemTest : FunSpec({

    fun worldWithPickup(): World = World().addSystem(PickupSystem())

    test("picks up an item on the player's cell: despawns it, adds to inventory, emits an event") {
        val world = worldWithPickup()
        val events = mutableListOf<ItemPickedUp>()
        world.events.subscribe<ItemPickedUp> { events.add(it) }
        val player = world.spawn(Player, Position(3, 3), ZoneMember("z"), Inventory()).id
        val item = world.spawn(Item("potion"), Position(3, 3), ZoneMember("z")).id

        world.tick()

        world.get(item) shouldBe null // despawned
        world.get(player)!!.require<Inventory>().items shouldBe listOf("potion")
        events.map { it.name } shouldBe listOf("potion")
    }

    test("ignores items on other cells or in other zones") {
        val world = worldWithPickup()
        val player = world.spawn(Player, Position(3, 3), ZoneMember("z"), Inventory()).id
        world.spawn(Item("scroll"), Position(4, 3), ZoneMember("z")) // adjacent cell
        world.spawn(Item("ring"), Position(3, 3), ZoneMember("other")) // same cell, other zone

        world.tick()

        world.get(player)!!.require<Inventory>().items shouldBe emptyList()
    }

    test("picks up multiple items stacked on the cell") {
        val world = worldWithPickup()
        val player = world.spawn(Player, Position(1, 1), ZoneMember("z"), Inventory()).id
        world.spawn(Item("potion"), Position(1, 1), ZoneMember("z"))
        world.spawn(Item("gold piece"), Position(1, 1), ZoneMember("z"))

        world.tick()

        world.get(player)!!.require<Inventory>().items.toSet() shouldBe setOf("potion", "gold piece")
    }

    test("no-op when the player has no Inventory component (item left in place)") {
        val world = worldWithPickup()
        world.spawn(Player, Position(2, 2), ZoneMember("z")) // no Inventory
        val item = world.spawn(Item("potion"), Position(2, 2), ZoneMember("z")).id

        world.tick()

        world.get(item).shouldNotBeNull()
    }
})
