package com.sletmoe.korogue.world

import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Tests for [SimulatedZonePolicy] (ADR-0021, Knob 1): [GameWorld.simulatedZones] delegates to a
 * consumer-swappable [GameWorld.simulatedZonePolicy], defaulting to [CurrentZoneOnly] so today's
 * behaviour is unchanged, with [CurrentPlusAdjacent] and arbitrary custom policies as opt-ins.
 */
class SimulatedZonePolicyTest : FunSpec({

    fun threeZoneWorld(): GameWorld =
        GameWorld.create {
            zone("a", 10, 10, isCurrentZone = true)
            zone("b", 10, 10)
            zone("c", 10, 10)
        }

    test("default policy is CurrentZoneOnly — simulatedZones is just {currentZoneId}") {
        val world = threeZoneWorld()

        world.simulatedZonePolicy shouldBe CurrentZoneOnly
        world.simulatedZones() shouldBe setOf("a")
    }

    test("CurrentPlusAdjacent adds the current zone's portal-neighbours") {
        val world = threeZoneWorld()
        world.ecs.spawn(Position(1, 1), ZoneMember("a"), Portal("b", 0, 0))
        world.simulatedZonePolicy = CurrentPlusAdjacent(PortalZoneAdjacency(world))

        world.simulatedZones() shouldContainExactlyInAnyOrder listOf("a", "b")
    }

    test("the policy is live — it recomputes after currentZoneId changes") {
        val world = threeZoneWorld()
        world.ecs.spawn(Position(1, 1), ZoneMember("a"), Portal("b", 0, 0))
        world.ecs.spawn(Position(2, 2), ZoneMember("b"), Portal("c", 0, 0))
        world.simulatedZonePolicy = CurrentPlusAdjacent(PortalZoneAdjacency(world))

        val beforeTransition = world.simulatedZones()
        world.currentZoneId = "b"
        val afterTransition = world.simulatedZones()

        beforeTransition shouldBe setOf("a", "b")
        afterTransition shouldBe setOf("b", "c")
    }

    test("an arbitrary custom SimulatedZonePolicy is honored") {
        val world = threeZoneWorld()
        world.simulatedZonePolicy = SimulatedZonePolicy { setOf("a", "b", "c") }

        world.simulatedZones() shouldContainExactlyInAnyOrder listOf("a", "b", "c")
    }
})
