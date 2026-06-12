package com.sletmoe.korogue.schedule

import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class SchedulerTest : FunSpec({

    // A tiny harness: run turns 0..lastTurn, advancing `scheduler` once per turn against `effects`.
    fun Scheduler.runTurns(
        lastTurn: Long,
        effects: Map<String, TimedEffect>,
    ) {
        val world = World()
        for (t in 0..lastTurn) {
            advance(world, TickContext(turn = t, elapsedMs = 0L, random = Random.Default)) { id ->
                effects.getValue(id)
            }
        }
    }

    test("a fuse fires exactly once, on the turn it's due, then is gone") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("boom" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        scheduler.fuse("boom", afterTurns = 3) // scheduled at turn 0 -> due turn 3
        scheduler.runTurns(lastTurn = 6, effects)

        fired shouldBe listOf(3L)
        scheduler.size shouldBe 0
    }

    test("a daemon fires every period, indefinitely") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("tick" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        scheduler.daemon("tick", everyTurns = 2) // due turns 2, 4, 6, ...
        scheduler.runTurns(lastTurn = 7, effects)

        fired shouldBe listOf(2L, 4L, 6L)
        scheduler.size shouldBe 1 // still scheduled
    }

    test("an every-turn daemon fires on each turn") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("each" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        scheduler.daemon("each", everyTurns = 1)
        scheduler.runTurns(lastTurn = 3, effects)

        fired shouldBe listOf(1L, 2L, 3L)
    }

    test("cancel removes a pending timer before it fires") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("boom" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        val handle = scheduler.fuse("boom", afterTurns = 3)
        scheduler.cancel(handle) shouldBe true
        scheduler.runTurns(lastTurn = 6, effects)

        fired shouldBe emptyList()
        scheduler.cancel(handle) shouldBe false // already gone
    }

    test("lengthen delays a fuse's firing") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("boom" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        val handle = scheduler.fuse("boom", afterTurns = 3) // due turn 3
        scheduler.lengthen(handle, byTurns = 2) // now due turn 5
        scheduler.runTurns(lastTurn = 6, effects)

        fired shouldBe listOf(5L)
    }

    test("lengthen with a negative delta hastens firing") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("boom" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        val handle = scheduler.fuse("boom", afterTurns = 5)
        scheduler.lengthen(handle, byTurns = -3) // due turn 2
        scheduler.runTurns(lastTurn = 6, effects)

        fired shouldBe listOf(2L)
    }

    test("multiple effects due the same turn fire in scheduling (handle) order") {
        val scheduler = Scheduler()
        val order = mutableListOf<String>()
        val effects =
            mapOf(
                "a" to TimedEffect { _, _, _ -> order += "a" },
                "b" to TimedEffect { _, _, _ -> order += "b" },
                "c" to TimedEffect { _, _, _ -> order += "c" },
            )

        scheduler.fuse("b", afterTurns = 1)
        scheduler.fuse("a", afterTurns = 1)
        scheduler.fuse("c", afterTurns = 1)
        scheduler.runTurns(lastTurn = 1, effects)

        order shouldBe listOf("b", "a", "c") // scheduling order, not alphabetical
    }

    test("an effect can schedule a follow-up fuse while firing") {
        val scheduler = Scheduler()
        val fired = mutableListOf<String>()
        val effects =
            mapOf(
                "first" to
                    TimedEffect { _, sched, _ ->
                        fired += "first"
                        sched.fuse("second", afterTurns = 2)
                    },
                "second" to TimedEffect { _, _, _ -> fired += "second" },
            )

        scheduler.fuse("first", afterTurns = 1) // fires turn 1, schedules second for turn 3
        scheduler.runTurns(lastTurn = 5, effects)

        fired shouldBe listOf("first", "second")
    }

    test("an effect can cancel another timer that hasn't fired yet this turn") {
        val scheduler = Scheduler()
        val fired = mutableListOf<String>()
        var victim: Scheduler.Handle? = null
        val effects =
            mapOf(
                // "killer" sorts before "victim" by handle, so it runs first and cancels the victim.
                "killer" to
                    TimedEffect { _, sched, _ ->
                        fired += "killer"
                        sched.cancel(victim!!)
                    },
                "victim" to TimedEffect { _, _, _ -> fired += "victim" },
            )

        scheduler.fuse("killer", afterTurns = 1)
        victim = scheduler.fuse("victim", afterTurns = 1)
        scheduler.runTurns(lastTurn = 1, effects)

        fired shouldBe listOf("killer") // victim was cancelled before its turn to fire
    }

    test("a daemon that an effect lengthens this turn keeps the deferred time, not its period") {
        val scheduler = Scheduler()
        val fired = mutableListOf<Long>()
        var daemonHandle: Scheduler.Handle? = null
        val effects =
            mapOf(
                "pusher" to TimedEffect { _, sched, _ -> sched.lengthen(daemonHandle!!, byTurns = 5) },
                "daemon" to TimedEffect { _, _, ctx -> fired += ctx.turn },
            )

        // Both are due turn 2; "pusher" gets the lower handle so it fires first and pushes the
        // daemon from turn 2 out to turn 7 before the daemon's turn to fire comes up this advance.
        scheduler.fuse("pusher", afterTurns = 2)
        daemonHandle = scheduler.daemon("daemon", everyTurns = 2)
        scheduler.runTurns(lastTurn = 5, effects)

        fired shouldBe emptyList() // pushed past turn 5, never fired in window
        scheduler.isScheduled(daemonHandle) shouldBe true
    }

    test("rejects non-positive delays") {
        val scheduler = Scheduler()
        shouldThrow<IllegalArgumentException> { scheduler.fuse("x", afterTurns = 0) }
        shouldThrow<IllegalArgumentException> { scheduler.daemon("x", everyTurns = 0) }
    }

    test("snapshot/restore preserves pending timers and handle allocation") {
        val original = Scheduler()
        val fired = mutableListOf<Long>()
        val effects = mapOf("boom" to TimedEffect { _, _, ctx -> fired += ctx.turn })

        // Advance a couple of turns so currentTurn and a handle are non-trivial.
        original.runTurns(lastTurn = 1, effects)
        original.fuse("boom", afterTurns = 3) // scheduled at currentTurn 1 -> due turn 4
        val state = original.snapshot()

        val restored = Scheduler().apply { restore(state) }
        restored.snapshot() shouldBe state
        // Resumes correctly: continue advancing from turn 2 onward.
        val world = World()
        for (t in 2L..6L) {
            restored.advance(world, TickContext(turn = t, elapsedMs = 0L, random = Random.Default)) { id ->
                effects.getValue(id)
            }
        }
        fired shouldBe listOf(4L)
    }

    test("a newly restored scheduler keeps allocating fresh, non-colliding handles") {
        val original = Scheduler()
        val h0 = original.fuse("x", afterTurns = 1)
        val restored = Scheduler().apply { restore(original.snapshot()) }

        val h1 = restored.fuse("y", afterTurns = 1)
        h1 shouldBe Scheduler.Handle(h0.value + 1) // continues the counter, no reuse
    }
})
