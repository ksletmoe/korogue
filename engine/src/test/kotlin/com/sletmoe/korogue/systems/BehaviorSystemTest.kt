package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Collision
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.world.CurrentPlusAdjacent
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.PortalZoneAdjacency
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class BehaviorSystemTest : FunSpec({

    fun ctx() = TickContext(turn = 0L, elapsedMs = 0L, random = Random(0))

    test("applies the resolved strategy's move as a MoveIntent") {
        val world =
            World().addSystem(
                BehaviorSystem(resolveStrategy = {
                    BehaviorStrategy {
                            _,
                            _,
                            _,
                        ->
                        MoveIntent(1, 0)
                    }
                }),
            )
        val id = world.spawn(Behavior("x"), Position(2, 2), ZoneMember("z")).id

        world.tick()

        world.get(id)!!.require<MoveIntent>() shouldBe MoveIntent(1, 0)
    }

    test("attaches no MoveIntent when the strategy returns null") {
        val world = World().addSystem(BehaviorSystem(resolveStrategy = { BehaviorStrategy { _, _, _ -> null } }))
        val id = world.spawn(Behavior("x"), Position(2, 2), ZoneMember("z")).id

        world.tick()

        world.get(id)!!.get<MoveIntent>().shouldBeNull()
    }

    test("skips an entity that already carries an AttackIntent this tick (krogue-4tn)") {
        val world =
            World().addSystem(
                BehaviorSystem(resolveStrategy = { BehaviorStrategy { _, _, _ -> MoveIntent(1, 0) } }),
            )
        val id = world.spawn(Behavior("x"), Position(2, 2), ZoneMember("z"), AttackIntent(EntityId(99))).id

        world.tick()

        world.get(id)!!.get<MoveIntent>().shouldBeNull()
    }

    test("only acts on entities in the active zones (ADR-0008 scoping)") {
        val world =
            World().addSystem(
                BehaviorSystem(
                    resolveStrategy = { BehaviorStrategy { _, _, _ -> MoveIntent(1, 0) } },
                    activeZones = { setOf("active") },
                ),
            )
        val here = world.spawn(Behavior("x"), Position(2, 2), ZoneMember("active")).id
        val dormant = world.spawn(Behavior("x"), Position(2, 2), ZoneMember("dormant")).id

        world.tick()

        world.get(here)!!.require<MoveIntent>() shouldBe MoveIntent(1, 0)
        world.get(dormant)!!.get<MoveIntent>().shouldBeNull()
    }

    test("HuntPlayerStrategy steps one tile toward the player when in range") {
        val world = World()
        world.spawn(Player, Position(5, 2), ZoneMember("z"))
        val hunter = world.spawn(Position(2, 2), ZoneMember("z")).id

        val move = HuntPlayerStrategy(range = 10, actChance = 1.0).decide(world, world.get(hunter)!!, ctx())

        move shouldBe MoveIntent(1, 0) // player is east
    }

    test("HuntPlayerStrategy stays put when the player is out of range") {
        val world = World()
        world.spawn(Player, Position(50, 50), ZoneMember("z"))
        val hunter = world.spawn(Position(2, 2), ZoneMember("z")).id

        val move = HuntPlayerStrategy(range = 10, actChance = 1.0).decide(world, world.get(hunter)!!, ctx())

        move.shouldBeNull()
    }

    // The documented consumer-extension path end-to-end (Phase 4d): write a strategy,
    // register it on a GameModule under a stable id, tag an entity with Behavior(id), and
    // let BehaviorSystem resolve + run it through the module's registry — no injected stub.
    test("a custom strategy registered on a GameModule drives BehaviorSystem by id") {
        val patrol = BehaviorStrategy { _, _, _ -> MoveIntent(0, 1) }
        val module = GameModule.engineDefaults().strategy("patrol", patrol).build()
        val world = World().addSystem(BehaviorSystem(resolveStrategy = module.strategies::resolve))
        val id = world.spawn(Behavior("patrol"), Position(2, 2), ZoneMember("z")).id

        world.tick()

        world.get(id)!!.require<MoveIntent>() shouldBe MoveIntent(0, 1)
    }

    // --- same-zone gating + cross-zone hunt (krogue-s67.4, ADR-0021 Knob 2) ----------------------

    test("HuntPlayerStrategy ignores a player in a different zone") {
        val world = World()
        world.spawn(Player, Position(5, 2), ZoneMember("other"))
        val hunter = world.spawn(Position(2, 2), ZoneMember("z")).id

        HuntPlayerStrategy(range = 10, actChance = 1.0).decide(world, world.get(hunter)!!, ctx()).shouldBeNull()
    }

    test("CrossZoneHuntPlayerStrategy hunts the player directly in the same zone") {
        val world = World()
        world.spawn(Player, Position(5, 2), ZoneMember("z"))
        val hunter = world.spawn(Position(2, 2), ZoneMember("z")).id

        CrossZoneHuntPlayerStrategy(range = 10, actChance = 1.0)
            .decide(world, world.get(hunter)!!, ctx()) shouldBe MoveIntent(1, 0)
    }

    test("CrossZoneHuntPlayerStrategy steps toward the portal to the player's zone when cross-zone") {
        val world = World()
        world.spawn(Player, Position(5, 5), ZoneMember("a"))
        world.spawn(Portal("a", 7, 7), Position(4, 2), ZoneMember("b")) // portal in b leading to a
        val hunter = world.spawn(Position(2, 2), ZoneMember("b")).id

        // player is in another zone, so head for the b->a portal at (4, 2): one step east
        CrossZoneHuntPlayerStrategy(range = 10, actChance = 1.0)
            .decide(world, world.get(hunter)!!, ctx()) shouldBe MoveIntent(1, 0)
    }

    test("CrossZoneHuntPlayerStrategy stays put when no portal leads to the player's zone") {
        val world = World()
        world.spawn(Player, Position(5, 5), ZoneMember("a"))
        world.spawn(Portal("c", 7, 7), Position(4, 2), ZoneMember("b")) // portal leads to c, not a
        val hunter = world.spawn(Position(2, 2), ZoneMember("b")).id

        CrossZoneHuntPlayerStrategy(
            range = 10,
            actChance = 1.0,
        ).decide(world, world.get(hunter)!!, ctx()).shouldBeNull()
    }

    test("CrossZoneHuntPlayerStrategy stays put when the connecting portal is out of range") {
        val world = World()
        world.spawn(Player, Position(5, 5), ZoneMember("a"))
        world.spawn(Portal("a", 7, 7), Position(50, 50), ZoneMember("b"))
        val hunter = world.spawn(Position(2, 2), ZoneMember("b")).id

        CrossZoneHuntPlayerStrategy(
            range = 10,
            actChance = 1.0,
        ).decide(world, world.get(hunter)!!, ctx()).shouldBeNull()
    }

    test("engineDefaults registers the cross-zone hunt strategy by id") {
        (CrossZoneHuntPlayerStrategy.ID in GameModule.engineDefaults().build().strategies) shouldBe true
    }

    // End-to-end (the s67 epic acceptance): CurrentPlusAdjacent keeps the monster's zone simulated,
    // CrossZoneHuntPlayerStrategy paths it to the portal, and the generalized PortalSystem carries
    // it across — all four pieces composing into a chase across a transition.
    test("cross-zone chase: a monster in an adjacent zone paths to the portal and crosses") {
        val gw =
            GameWorld.create {
                zone("a", 10, 10, isCurrentZone = true)
                zone("b", 10, 10)
            }
        gw.simulatedZonePolicy = CurrentPlusAdjacent(PortalZoneAdjacency(gw))
        // a->b portal makes b adjacent to a (so b is simulated); b->a portal is the monster's exit.
        gw.ecs.spawn(Portal("b", 1, 1), Position(9, 9), ZoneMember("a"), Collision.PASSABLE)
        gw.ecs.spawn(Portal("a", 7, 7), Position(3, 1), ZoneMember("b"), Collision.PASSABLE)
        gw.ecs.spawn(Player, Position(5, 5), ZoneMember("a"))

        val module =
            GameModule
                .engineDefaults()
                .strategy(CrossZoneHuntPlayerStrategy.ID, CrossZoneHuntPlayerStrategy(actChance = 1.0))
                .build()
        gw.ecs.addSystem(BehaviorSystem(resolveStrategy = module.strategies::resolve, activeZones = gw::simulatedZones))
        gw.ecs.addSystem(MovementSystem(gw.zones))
        gw.ecs.addSystem(PortalSystem(gw))
        val monster = gw.ecs.spawn(Behavior(CrossZoneHuntPlayerStrategy.ID), Position(1, 1), ZoneMember("b")).id

        // (1,1)->(2,1)->(3,1) onto the b->a portal, then carried to a(7,7).
        repeat(2) { gw.ecs.tick() }

        gw.ecs.get(monster)!!.require<ZoneMember>().zoneId shouldBe "a"
        gw.ecs.get(monster)!!.require<Position>() shouldBe Position(7, 7)
        gw.currentZoneId shouldBe "a" // a monster crossing must NOT move the active zone (player-only)
    }
})
