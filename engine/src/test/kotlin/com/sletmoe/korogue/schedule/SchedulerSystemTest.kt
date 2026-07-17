package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.registry.Registry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SchedulerSystemTest : FunSpec({

    test("advances the scheduler once per world tick, firing timers on their turn") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("tick" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        val world = World()
        world.addSystem(SchedulerSystem(scheduler, Registry.of(effects)))
        scheduler.daemon("tick", everyTurns = 2) // due turns 2, 4, ...

        repeat(6) { world.tick() } // turns 0..5

        fired shouldBe listOf(2L, 4L)
    }

    test("a timed effect can mutate the ECS world it's handed (e.g. spawn an entity)") {
        val scheduler = Scheduler()
        val effects =
            mapOf(
                "spawn" to TimedEffect { world, _, _ -> world.spawn() },
            )

        val world = World()
        world.addSystem(SchedulerSystem(scheduler, Registry.of(effects)))
        scheduler.fuse("spawn", afterTurns = 1)

        world.entityCount shouldBe 0
        repeat(3) { world.tick() }

        world.entityCount shouldBe 1
    }
})
