package com.sletmoe.korogue.pipeline

import com.sletmoe.korogue.ecs.PipelineOrderException
import com.sletmoe.korogue.ecs.PipelineStage
import com.sletmoe.korogue.ecs.Staged
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * The ordering check behind krogue-32d: registering the built-in pipeline wrong used to be a
 * *silent* correctness bug (stale visibility, dropped intents) rather than a crash, so these
 * assert the check actually fires — a validator that cannot fail is worse than none.
 */
class PipelineOrderTest : FunSpec({

    // Stand-ins so the ordering rules are tested on their own, without dragging in each real
    // system's dependencies. They declare the same stages the built-ins do.
    class StagedStub(
        override val stage: PipelineStage,
    ) : Staged {
        override fun update(
            world: World,
            ctx: TickContext,
        ) = Unit
    }

    test("perception registered before lighting is rejected -- the constraint that motivated the check") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.PERCEPTION))
                .addSystem(StagedStub(StandardStage.LIGHTING))

        val error = shouldThrow<PipelineOrderException> { world.validateSystemOrder() }

        error.message shouldContain "PERCEPTION"
        error.message shouldContain "LIGHTING"
    }

    test("lighting registered before movement is rejected -- the moving-emitter (lantern) dependency") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.LIGHTING))
                .addSystem(StagedStub(StandardStage.MOVEMENT))

        val error = shouldThrow<PipelineOrderException> { world.validateSystemOrder() }

        error.message shouldContain "LIGHTING"
        error.message shouldContain "MOVEMENT"
    }

    test("perception before movement is rejected even with no lighting stage present") {
        // Perception names MOVEMENT directly, not only via LIGHTING, so a game with perception but no
        // lighting still has its line-of-sight-reads-moved-position dependency enforced.
        val world =
            World()
                .addSystem(StagedStub(StandardStage.PERCEPTION))
                .addSystem(StagedStub(StandardStage.MOVEMENT))

        shouldThrow<PipelineOrderException> { world.validateSystemOrder() }
    }

    test("lighting before perception is accepted") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.LIGHTING))
                .addSystem(StagedStub(StandardStage.PERCEPTION))

        shouldNotThrowAny { world.validateSystemOrder() }
    }

    test("tick validates on its own, so a mis-ordered pipeline fails on turn one") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.COMBAT))
                .addSystem(StagedStub(StandardStage.MOVEMENT))

        shouldThrow<PipelineOrderException> { world.tick() }
    }

    test("a violation introduced after an earlier clean tick is still caught") {
        val world = World().addSystem(StagedStub(StandardStage.MOVEMENT))
        world.tick() // validates clean, latching the flag

        world.addSystem(StagedStub(StandardStage.BEHAVIOR)) // BEHAVIOR must precede MOVEMENT

        shouldThrow<PipelineOrderException> { world.tick() }
    }

    test("systems that don't declare a stage are unconstrained and never trip the check") {
        val lambdaSystem = System { _, _ -> }
        val world =
            World()
                .addSystem(lambdaSystem)
                .addSystem(StagedStub(StandardStage.PERCEPTION))
                .addSystem(lambdaSystem)
                .addSystem(StagedStub(StandardStage.LIGHTING))
                .addSystem(lambdaSystem)

        // Still rejected for the staged pair, but the lambdas neither help nor hurt.
        shouldThrow<PipelineOrderException> { world.validateSystemOrder() }
    }

    test("a constraint against an absent stage is vacuous -- omitting a built-in is not an error") {
        // The Rogue port registers no BEHAVIOR system at all; MOVEMENT must not therefore be invalid.
        val world = World().addSystem(StagedStub(StandardStage.MOVEMENT))

        shouldNotThrowAny { world.validateSystemOrder() }
    }

    test("a game's replacement for a built-in inherits that stage's constraints") {
        // The shape the Rogue port relies on: RogueCombatSystem stands in for CombatSystem and
        // declares COMBAT, so it is held to "COMBAT runs after MOVEMENT" like the original.
        class CustomCombatSystem : Staged {
            override val stage: PipelineStage get() = StandardStage.COMBAT

            override fun update(
                world: World,
                ctx: TickContext,
            ) = Unit
        }

        val bad = World().addSystem(CustomCombatSystem()).addSystem(StagedStub(StandardStage.MOVEMENT))
        val good = World().addSystem(StagedStub(StandardStage.MOVEMENT)).addSystem(CustomCombatSystem())

        shouldThrow<PipelineOrderException> { bad.validateSystemOrder() }
        shouldNotThrowAny { good.validateSystemOrder() }
    }

    test("independent stages may be registered in either order (the order is partial, not total)") {
        // The demo picks up before resolving combat; the Rogue port does the reverse. Both are
        // correct, so encoding a PICKUP/COMBAT constraint would wrongly reject one of them.
        val pickupFirst =
            World()
                .addSystem(StagedStub(StandardStage.MOVEMENT))
                .addSystem(StagedStub(StandardStage.PICKUP))
                .addSystem(StagedStub(StandardStage.COMBAT))
        val combatFirst =
            World()
                .addSystem(StagedStub(StandardStage.MOVEMENT))
                .addSystem(StagedStub(StandardStage.COMBAT))
                .addSystem(StagedStub(StandardStage.PICKUP))

        shouldNotThrowAny { pickupFirst.validateSystemOrder() }
        shouldNotThrowAny { combatFirst.validateSystemOrder() }
    }

    test("SCHEDULE is unconstrained: running timers late is unusual, not wrong") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.MOVEMENT))
                .addSystem(StagedStub(StandardStage.SCHEDULE))

        shouldNotThrowAny { world.validateSystemOrder() }
    }

    test("the message names both systems and their stages, so the fix is obvious") {
        val world =
            World()
                .addSystem(StagedStub(StandardStage.BEHAVIOR))
                .addSystem(StagedStub(StandardStage.RANGED_ATTACK))

        val error = shouldThrow<PipelineOrderException> { world.validateSystemOrder() }

        error.message shouldContain "StagedStub"
        error.message shouldContain "must run after"
    }
})
