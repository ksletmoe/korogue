package com.sletmoe.korogue.random

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SerializableRandomTest : FunSpec({

    test("same seed produces the same sequence") {
        val a = SerializableRandom.fromSeed(42)
        val b = SerializableRandom.fromSeed(42)
        val seqA = List(100) { a.nextInt() }
        val seqB = List(100) { b.nextInt() }
        seqA shouldBe seqB
    }

    test("different seeds produce different sequences") {
        val a = SerializableRandom.fromSeed(1)
        val b = SerializableRandom.fromSeed(2)
        List(20) { a.nextInt() } shouldNotBe List(20) { b.nextInt() }
    }

    test("captured state resumes the exact same sequence") {
        val rng = SerializableRandom.fromSeed(7)
        repeat(50) { rng.nextInt() } // advance
        val resumedRng = SerializableRandom.fromState(rng.state())

        val continued = List(30) { rng.nextInt() }
        val resumed = List(30) { resumedRng.nextInt() }

        resumed shouldBe continued
    }

    test("an all-zero state is rejected (xoshiro must not be all-zero) yet still generates") {
        val rng = SerializableRandom.fromState(RandomState(0, 0, 0, 0))
        // Would be stuck at 0 forever if the all-zero guard were missing.
        List(10) { rng.nextLong() }.any { it != 0L } shouldBe true
    }
})
