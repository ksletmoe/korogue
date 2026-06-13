package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.perception.PerceptionModel
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Integration tests for [PerceptionSystem] — the per-observer cache for [Perceived], the perception
 * analogue of [LightingSystem] rewriting `lightMap` (ADR-0015). Uses a counting fake [PerceptionModel]
 * so a refresh is observable across ticks without depending on a real sense pipeline.
 */
class PerceptionSystemTest : DescribeSpec({

    /** A model that records how many times it's been asked, encoding the count into the result. */
    class CountingModel : PerceptionModel {
        var calls = 0
        override fun perceive(
            observer: Entity,
            world: GameWorld,
        ): Perceived {
            calls++
            return Perceived(zoneId = "marker-$calls", entities = setOf(EntityId(calls.toLong())))
        }
    }

    fun gameWorld(zones: List<String> = listOf("z")): GameWorld {
        val world = World()
        val zoneMap = zones.associateWith { Zone(it, Grid(8, 8, BLANK_TILE)) }
        return GameWorld(world, zoneMap, zones.first())
    }

    describe("PerceptionSystem") {
        it("writes a fresh Perceived onto every entity that opts in by carrying one") {
            val gw = gameWorld()
            val model = CountingModel()
            val observer = gw.ecs.spawn(ZoneMember("z"), Perceived())
            gw.ecs.addSystem(PerceptionSystem(gw, model))

            gw.ecs.tick()

            val perceived = gw.ecs.get(observer.id)!!.get<Perceived>()!!
            perceived.zoneId shouldBe "marker-1"
            perceived.entities shouldBe setOf(EntityId(1))
        }

        it("refreshes the cache each tick (perception changes on a tick, sampled many times)") {
            val gw = gameWorld()
            val model = CountingModel()
            val observer = gw.ecs.spawn(ZoneMember("z"), Perceived())
            gw.ecs.addSystem(PerceptionSystem(gw, model))

            gw.ecs.tick()
            gw.ecs.tick()

            gw.ecs.get(observer.id)!!.get<Perceived>()!!.zoneId shouldBe "marker-2"
            model.calls shouldBe 2
        }

        it("ignores entities that have not opted in (no Perceived component)") {
            val gw = gameWorld()
            val model = CountingModel()
            val plain = gw.ecs.spawn(ZoneMember("z"))
            gw.ecs.addSystem(PerceptionSystem(gw, model))

            gw.ecs.tick()

            gw.ecs.get(plain.id)!!.get<Perceived>().shouldBeNull()
            model.calls shouldBe 0
        }

        it("only refreshes observers in an active zone; a dormant observer keeps its prior Perceived") {
            val gw = gameWorld(listOf("active", "dormant"))
            val model = CountingModel()
            val stale = Perceived(zoneId = "stale")
            val dormantObserver = gw.ecs.spawn(ZoneMember("dormant"), stale)
            val activeObserver = gw.ecs.spawn(ZoneMember("active"), Perceived())
            gw.ecs.addSystem(PerceptionSystem(gw, model, activeZones = { setOf("active") }))

            gw.ecs.tick()

            gw.ecs.get(dormantObserver.id)!!.get<Perceived>()!! shouldBe stale // untouched
            gw.ecs.get(activeObserver.id)!!.get<Perceived>()!!.zoneId shouldBe "marker-1"
        }
    }
})
