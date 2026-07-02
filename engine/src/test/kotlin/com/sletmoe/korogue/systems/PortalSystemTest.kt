package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.events.ZoneChanged
import com.sletmoe.korogue.world.GameWorld
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class PortalSystemTest : FunSpec({

    fun twoZoneWorld(): GameWorld =
        GameWorld.create {
            zone("a", 10, 10, isCurrentZone = true)
            zone("b", 10, 10)
        }

    test("steps the player through a portal it stands on and switches the current zone") {
        val gw = twoZoneWorld()
        gw.ecs.addSystem(PortalSystem(gw))
        gw.ecs.spawn(Position(3, 3), ZoneMember("a"), Portal("b", 7, 7))
        val player = gw.ecs.spawn(Player, Position(3, 3), ZoneMember("a")).id

        gw.ecs.tick()

        gw.ecs.get(player)!!.require<Position>() shouldBe Position(7, 7)
        gw.ecs.get(player)!!.require<ZoneMember>().zoneId shouldBe "b"
        gw.currentZoneId shouldBe "b"
    }

    test("does nothing when the player is not standing on a portal") {
        val gw = twoZoneWorld()
        gw.ecs.addSystem(PortalSystem(gw))
        gw.ecs.spawn(Position(3, 3), ZoneMember("a"), Portal("b", 7, 7))
        val player = gw.ecs.spawn(Player, Position(5, 5), ZoneMember("a")).id

        gw.ecs.tick()

        gw.ecs.get(player)!!.require<ZoneMember>().zoneId shouldBe "a"
        gw.currentZoneId shouldBe "a"
    }

    test("publishes a ZoneChanged when the player transitions, and none when it does not") {
        val gw = twoZoneWorld()
        gw.ecs.addSystem(PortalSystem(gw))
        val changes = mutableListOf<ZoneChanged>()
        gw.ecs.events.subscribe<ZoneChanged> { changes.add(it) }
        gw.ecs.spawn(Position(3, 3), ZoneMember("a"), Portal("b", 7, 7))
        val player = gw.ecs.spawn(Player, Position(5, 5), ZoneMember("a")).id

        gw.ecs.tick() // off the portal — no transition, no event
        changes.shouldBeEmpty()

        gw.ecs.set(player, Position(3, 3)) // step onto the portal
        gw.ecs.tick()

        changes.single().let {
            it.entity shouldBe player
            it.from shouldBe "a"
            it.to shouldBe "b"
        }
    }

    test("a non-player entity standing on a portal transitions without changing the current zone") {
        val gw = twoZoneWorld()
        gw.ecs.addSystem(PortalSystem(gw))
        val changes = mutableListOf<ZoneChanged>()
        gw.ecs.events.subscribe<ZoneChanged> { changes.add(it) }
        gw.ecs.spawn(Position(3, 3), ZoneMember("a"), Portal("b", 7, 7))
        val monster = gw.ecs.spawn(Position(3, 3), ZoneMember("a")).id

        gw.ecs.tick()

        gw.ecs.get(monster)!!.require<Position>() shouldBe Position(7, 7)
        gw.ecs.get(monster)!!.require<ZoneMember>().zoneId shouldBe "b"
        gw.currentZoneId shouldBe "a" // only the player's crossing switches the active zone

        changes.single().let {
            it.entity shouldBe monster
            it.from shouldBe "a"
            it.to shouldBe "b"
        }
    }

    test("both a monster and the player transition through the same portal in one tick") {
        val gw = twoZoneWorld()
        gw.ecs.addSystem(PortalSystem(gw))
        gw.ecs.spawn(Position(3, 3), ZoneMember("a"), Portal("b", 7, 7))
        val monster = gw.ecs.spawn(Position(3, 3), ZoneMember("a")).id
        val player = gw.ecs.spawn(Player, Position(3, 3), ZoneMember("a")).id

        gw.ecs.tick()

        gw.ecs.get(monster)!!.require<ZoneMember>().zoneId shouldBe "b"
        gw.ecs.get(player)!!.require<ZoneMember>().zoneId shouldBe "b"
        gw.currentZoneId shouldBe "b"
    }
})
