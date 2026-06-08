package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.MoveIntent
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World
import com.sletmoe.krogue.registry.GameModule
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
})
