package com.sletmoe.korogue.world

import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Tests for [GameWorld]'s dynamic zone registry (ADR-0016, krogue-go8): a world can grow and shrink
 * its set of zones over a session, and the [GameWorld.zones] view a system captured at wiring time
 * keeps reflecting those changes.
 */
class GameWorldTest : FunSpec({

    fun world(): GameWorld =
        GameWorld.create {
            zone("start", 4, 4, isCurrentZone = true)
        }

    test("addZone registers a zone you can then enter") {
        val world = world()
        world.addZone(Zone.create("next", 3, 3))

        world.zones.keys shouldContainExactlyInAnyOrder listOf("start", "next")
        world.currentZoneId = "next" // now a legal switch
        world.currentZone.zoneId shouldBe "next"
    }

    test("the zones view is live — a captured reference sees later additions") {
        val world = world()
        val captured: Map<String, Zone> = world.zones // as a system would hold it

        world.addZone(Zone.create("next", 3, 3))

        captured.containsKey("next") shouldBe true
    }

    test("removeZone discards a zone's terrain") {
        val world = world()
        world.addZone(Zone.create("next", 3, 3))
        world.currentZoneId = "next"

        world.removeZone("start")

        world.zones.keys shouldNotContain "start"
    }

    test("the current zone cannot be removed") {
        val world = world()
        shouldThrow<RuntimeException> { world.removeZone("start") }
    }

    test("switching to an unregistered zone is rejected") {
        val world = world()
        shouldThrow<RuntimeException> { world.currentZoneId = "ghost" }
    }

    test("relocate sets ZoneMember and Position together, atomically (ADR-0021 Mechanic B)") {
        val world = world()
        world.addZone(Zone.create("next", 3, 3))
        val entity = world.ecs.spawn(Position(1, 1), ZoneMember("start")).id

        world.relocate(entity, "next", 2, 2)

        world.ecs.get(entity)!!.require<ZoneMember>().zoneId shouldBe "next"
        world.ecs.get(entity)!!.require<Position>() shouldBe Position(2, 2)
    }

    test("relocate is a no-op for an unknown entity id") {
        val world = world()
        world.addZone(Zone.create("next", 3, 3))
        val entity = world.ecs.spawn(Position(1, 1), ZoneMember("start")).id
        world.ecs.despawn(entity)

        // Should not throw — matches World.set's no-op-on-unknown-id contract.
        world.relocate(entity, "next", 2, 2)
    }
})
