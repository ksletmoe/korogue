package com.sletmoe.korogue.loop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GameLoopTest : FunSpec({

    test("real-time advances every frame, regardless of player input") {
        val loop = RealTimeLoop()
        var ticks = 0
        loop.advance { ticks++ }
        loop.advance { ticks++ } // no requestTurn — still advances
        ticks shouldBe 2

        loop.requestTurn() // no-op in real-time
        loop.advance { ticks++ }
        ticks shouldBe 3
    }

    test("turn-based advances only on a requested turn, once per request") {
        val loop = TurnBasedLoop()
        var ticks = 0

        loop.advance { ticks++ }
        ticks shouldBe 0 // no action requested -> world frozen

        loop.requestTurn()
        loop.advance { ticks++ }
        ticks shouldBe 1 // one turn

        loop.advance { ticks++ }
        ticks shouldBe 1 // request consumed -> frozen again
    }

    test("turn-based coalesces multiple requests before an advance into a single turn") {
        val loop = TurnBasedLoop()
        var ticks = 0

        loop.requestTurn()
        loop.requestTurn()
        loop.advance { ticks++ }

        ticks shouldBe 1
    }
})
