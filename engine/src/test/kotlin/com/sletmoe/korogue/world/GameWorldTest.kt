package com.sletmoe.korogue.world

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
})
