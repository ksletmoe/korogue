package com.sletmoe.korogue.world

import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Tests for [PortalZoneAdjacency] (ADR-0021, Mechanic A): a zone's neighbours are the
 * distinct [Portal.targetZoneId]s of the portals standing in it, and the seam is
 * overridable via [ZoneAdjacency] for non-portal adjacency schemes.
 */
class ZoneAdjacencyTest : FunSpec({

    fun threeZoneWorld(): GameWorld =
        GameWorld.create {
            zone("a", 10, 10, isCurrentZone = true)
            zone("b", 10, 10)
            zone("c", 10, 10)
        }

    test("neighborsOf returns the distinct target zones of a zone's portals") {
        val gw = threeZoneWorld()
        gw.ecs.spawn(Position(1, 1), ZoneMember("a"), Portal("b", 0, 0))
        gw.ecs.spawn(Position(2, 2), ZoneMember("a"), Portal("c", 0, 0))

        PortalZoneAdjacency(gw).neighborsOf("a") shouldContainExactlyInAnyOrder listOf("b", "c")
    }

    test("a zone with no portals has no neighbours") {
        val gw = threeZoneWorld()
        gw.ecs.spawn(Position(1, 1), ZoneMember("a"), Portal("b", 0, 0))

        PortalZoneAdjacency(gw).neighborsOf("b").shouldBeEmpty()
    }

    test("duplicate portals to the same target zone collapse to one neighbour") {
        val gw = threeZoneWorld()
        gw.ecs.spawn(Position(1, 1), ZoneMember("a"), Portal("b", 0, 0))
        gw.ecs.spawn(Position(2, 2), ZoneMember("a"), Portal("b", 5, 5))

        PortalZoneAdjacency(gw).neighborsOf("a") shouldBe setOf("b")
    }

    test("portals in other zones don't leak in") {
        val gw = threeZoneWorld()
        gw.ecs.spawn(Position(1, 1), ZoneMember("b"), Portal("c", 0, 0))

        PortalZoneAdjacency(gw).neighborsOf("a").shouldBeEmpty()
    }

    test("a custom ZoneAdjacency can override the portal-derived default") {
        val custom = ZoneAdjacency { zoneId -> if (zoneId == "a") setOf("z1", "z2") else emptySet() }

        custom.neighborsOf("a") shouldContainExactlyInAnyOrder listOf("z1", "z2")
        custom.neighborsOf("b").shouldBeEmpty()
    }
})
