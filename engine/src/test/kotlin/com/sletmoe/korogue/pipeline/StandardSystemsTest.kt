package com.sletmoe.korogue.pipeline

import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.perception.PerceptionSystem
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.schedule.Scheduler
import com.sletmoe.korogue.schedule.SchedulerSystem
import com.sletmoe.korogue.systems.BehaviorStrategy
import com.sletmoe.korogue.systems.BehaviorSystem
import com.sletmoe.korogue.systems.LightingSystem
import com.sletmoe.korogue.systems.MovementSystem
import com.sletmoe.korogue.world.GameWorld
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * krogue-32d: the standard pipeline as one call. These drive the *real* built-in systems through a
 * real GameModule rather than stubs, since the point of the helper is that the wiring it produces
 * actually runs.
 */
class StandardSystemsTest : io.kotest.core.spec.style.FunSpec({

    val module = GameModule.engineDefaults().build()

    fun gameWorld(): GameWorld =
        GameWorld.create {
            zone("z", 10, 10, isCurrentZone = true)
        }

    test("installs a pipeline that passes the engine's own ordering check") {
        val gw = gameWorld()

        gw.installStandardSystems(module)

        shouldNotThrowAny { gw.ecs.validateSystemOrder() }
    }

    test("registers the built-ins in their known-good order") {
        val gw = gameWorld()

        gw.installStandardSystems(module, scheduler = Scheduler())

        val stages = gw.ecs.systems().filterIsInstance<Staged>().map { it.stage }
        stages shouldContainInOrder
            listOf(
                StandardStage.SCHEDULE,
                StandardStage.RANGED_ATTACK,
                StandardStage.BEHAVIOR,
                StandardStage.MOVEMENT,
                StandardStage.PORTAL,
                StandardStage.PICKUP,
                StandardStage.COMBAT,
                StandardStage.LIGHTING,
                StandardStage.PERCEPTION,
            )
    }

    test("omits the scheduler when no Scheduler is given, and includes it when one is") {
        gameWorld().also { it.installStandardSystems(module) }.ecs
            .systems()
            .filterIsInstance<SchedulerSystem>()
            .shouldBe(emptyList())

        gameWorld().also { it.installStandardSystems(module, scheduler = Scheduler()) }.ecs
            .systems()
            .filterIsInstance<SchedulerSystem>()
            .size shouldBe 1
    }

    test("hands back the systems a host must drive outside the tick loop") {
        val gw = gameWorld()

        val systems = gw.installStandardSystems(module)

        // The demo runs lighting+perception once at startup so the first frame isn't black; that
        // requires holding the instances, not just registering them.
        systems.lighting.shouldBeInstanceOf<LightingSystem>()
        systems.perception.shouldBeInstanceOf<PerceptionSystem>()
        systems.scheduler shouldBe null
        gw.ecs.systems() shouldContainInOrder listOf(systems.movement, systems.combat)
    }

    test("the installed pipeline actually runs: AI drives movement and the player's Perceived is filled") {
        // A deterministic strategy rather than the built-in "wander", whose 2% act chance would make
        // the assertion a coin toss on the seed rather than a statement about the pipeline.
        val alwaysEast =
            GameModule
                .engineDefaults()
                .strategy("always-east", BehaviorStrategy { _, _, _ -> MoveIntent(1, 0) })
                .build()
        val gw = gameWorld()
        gw.installStandardSystems(alwaysEast)
        val player = gw.ecs.spawn(Player, Position(5, 5), ZoneMember("z"), Health(100, 100), Perceived()).id
        val mover = gw.ecs.spawn(Behavior("always-east"), Position(2, 2), ZoneMember("z")).id

        gw.ecs.tick()

        // BEHAVIOR emitted the intent and MOVEMENT consumed it, in that order, within one tick.
        gw.ecs.get(mover)!!.require<Position>() shouldBe Position(3, 2)
        // PERCEPTION ran (after LIGHTING) and cached a result for the opted-in observer.
        gw.ecs.get(player)!!.get<Perceived>().shouldNotBeNull().zoneId shouldBe "z"
    }

    test("resolves the default perception model from the module when none is passed") {
        val gw = gameWorld()

        val systems = gw.installStandardSystems(module)

        systems.perception.shouldBeInstanceOf<PerceptionSystem>()
    }

    test("a game may install the baseline and still add its own systems around it") {
        val gw = gameWorld()

        val systems = gw.installStandardSystems(module)
        gw.ecs.addSystem { _, _ -> } // a custom, unstaged system appended afterwards

        shouldNotThrowAny { gw.ecs.validateSystemOrder() }
        gw.ecs.systems().filterIsInstance<BehaviorSystem>().size shouldBe 1
        gw.ecs.systems().filterIsInstance<MovementSystem>().single() shouldBe systems.movement
    }
})
