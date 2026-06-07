package com.sletmoe.krogue.random

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class GameRandomTest : FunSpec({

    test("same master seed yields the same sequence for a given stream") {
        val a = GameRandom.fromSeed(123).stream("worldgen")
        val b = GameRandom.fromSeed(123).stream("worldgen")
        List(50) { a.nextInt() } shouldBe List(50) { b.nextInt() }
    }

    test("streams off one seed are independent — draining one does not shift another") {
        // The shareable-seed guarantee: combat draws must not change worldgen output.
        val g1 = GameRandom.fromSeed(123)
        val worldgenOnly = List(50) { g1.stream("worldgen").nextInt() }

        val g2 = GameRandom.fromSeed(123)
        repeat(1000) { g2.stream("combat").nextInt() } // hammer a different stream first
        val worldgenAfterCombat = List(50) { g2.stream("worldgen").nextInt() }

        worldgenAfterCombat shouldBe worldgenOnly
    }

    test("different stream names give different sequences") {
        val g = GameRandom.fromSeed(123)
        val worldgen = List(20) { g.stream("worldgen").nextInt() }
        val combat = List(20) { g.stream("combat").nextInt() }
        worldgen shouldNotBe combat
    }

    test("snapshot then restore resumes every stream exactly") {
        val g = GameRandom.fromSeed(999)
        repeat(40) { g.stream("ai").nextInt() }
        repeat(15) { g.stream("worldgen").nextInt() }
        val snap = g.snapshot()

        val continuedAi = List(10) { g.stream("ai").nextInt() }
        val continuedWorldgen = List(10) { g.stream("worldgen").nextInt() }

        val restored = GameRandom.restore(snap)
        restored.masterSeed shouldBe 999
        List(10) { restored.stream("ai").nextInt() } shouldBe continuedAi
        List(10) { restored.stream("worldgen").nextInt() } shouldBe continuedWorldgen
    }

    test("a stream not captured in a snapshot still re-derives deterministically from the seed") {
        val fresh = GameRandom.fromSeed(999).stream("loot")
        val viaRestore = GameRandom.restore(GameRandom.fromSeed(999).snapshot()).stream("loot")
        List(20) { fresh.nextInt() } shouldBe List(20) { viaRestore.nextInt() }
    }
})
