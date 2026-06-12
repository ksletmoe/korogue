package com.sletmoe.korogue.loop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GameLoopTest : FunSpec({

    test("real-time advances one tick per whole step of accumulated time, regardless of input") {
        val loop = RealTimeLoop(stepMs = 100L)
        var ticks = 0

        loop.advance(50L) { ticks++ }
        ticks shouldBe 0 // under one step -> not yet

        loop.advance(50L) { ticks++ }
        ticks shouldBe 1 // 50 + 50 = 100 -> one step

        loop.requestTurn() // no-op in real-time
        loop.advance(100L) { ticks++ }
        ticks shouldBe 2
    }

    test("real-time carries the sub-step remainder across frames") {
        val loop = RealTimeLoop(stepMs = 100L)
        var ticks = 0

        loop.advance(150L) { ticks++ } // one tick, 50ms remainder kept
        ticks shouldBe 1

        loop.advance(50L) { ticks++ } // 50 + 50 = 100 -> second tick
        ticks shouldBe 2
    }

    test("real-time runs multiple catch-up ticks for a long frame, up to the cap") {
        val loop = RealTimeLoop(stepMs = 100L, maxCatchUpSteps = 5)
        var ticks = 0

        loop.advance(350L) { ticks++ } // 3 whole steps
        ticks shouldBe 3
    }

    test("real-time caps catch-up and drops the backlog to avoid the spiral of death") {
        val loop = RealTimeLoop(stepMs = 100L, maxCatchUpSteps = 5)
        var ticks = 0

        loop.advance(1_000L) { ticks++ } // 10 steps owed, but capped at 5
        ticks shouldBe 5

        // Backlog was dropped, not banked: the next frame starts fresh.
        loop.advance(50L) { ticks++ }
        ticks shouldBe 5
        loop.advance(50L) { ticks++ }
        ticks shouldBe 6
    }

    test("real-time rejects non-positive configuration") {
        shouldThrow<IllegalArgumentException> { RealTimeLoop(stepMs = 0L) }
        shouldThrow<IllegalArgumentException> { RealTimeLoop(maxCatchUpSteps = 0) }
    }

    test("turn-based advances only on a requested turn, once per request, ignoring time") {
        val loop = TurnBasedLoop()
        var ticks = 0

        loop.advance(1_000L) { ticks++ }
        ticks shouldBe 0 // no action requested -> world frozen, time irrelevant

        loop.requestTurn()
        loop.advance(0L) { ticks++ }
        ticks shouldBe 1 // one turn

        loop.advance(1_000L) { ticks++ }
        ticks shouldBe 1 // request consumed -> frozen again
    }

    test("turn-based coalesces multiple requests before an advance into a single turn") {
        val loop = TurnBasedLoop()
        var ticks = 0

        loop.requestTurn()
        loop.requestTurn()
        loop.advance(0L) { ticks++ }

        ticks shouldBe 1
    }
})
