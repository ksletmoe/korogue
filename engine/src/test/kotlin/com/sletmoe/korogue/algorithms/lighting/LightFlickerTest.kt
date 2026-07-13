package com.sletmoe.korogue.algorithms.lighting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class LightFlickerTest : FunSpec({

    test("factorAt(0) with no seed starts at the unmodulated value (sin(0) = 0)") {
        val flicker = LightFlicker(amplitude = 0.2, periodMs = 1000)
        flicker.factorAt(0) shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("factorAt oscillates within [1 - amplitude, 1 + amplitude]") {
        val flicker = LightFlicker(amplitude = 0.2, periodMs = 400)
        for (t in 0L until 400L step 10) {
            val factor = flicker.factorAt(t)
            (factor >= 0.8 - 1e-9) shouldBe true
            (factor <= 1.2 + 1e-9) shouldBe true
        }
    }

    test("factorAt is periodic: one full period returns to the same value") {
        val flicker = LightFlicker(amplitude = 0.15, periodMs = 300, seed = 50)
        flicker.factorAt(1234) shouldBe (flicker.factorAt(1234 + 300) plusOrMinus 1e-9)
    }

    test("a quarter-period in reaches the peak (1 + amplitude)") {
        val flicker = LightFlicker(amplitude = 0.3, periodMs = 1000)
        flicker.factorAt(250) shouldBe (1.3 plusOrMinus 1e-9)
    }

    test("a three-quarter-period in reaches the trough (1 - amplitude)") {
        val flicker = LightFlicker(amplitude = 0.3, periodMs = 1000)
        flicker.factorAt(750) shouldBe (0.7 plusOrMinus 1e-9)
    }

    test("seed shifts the phase: two flickers differing only by seed disagree at t=0") {
        val a = LightFlicker(amplitude = 0.2, periodMs = 1000, seed = 0)
        val b = LightFlicker(amplitude = 0.2, periodMs = 1000, seed = 250)
        a.factorAt(0) shouldBe (1.0 plusOrMinus 1e-9)
        b.factorAt(0) shouldBe (1.2 plusOrMinus 1e-9) // b's t=0 is a's t=250 (quarter period -> peak)
    }

    test("amplitude 0 never deviates from 1.0, regardless of time") {
        val flicker = LightFlicker(amplitude = 0.0, periodMs = 400)
        flicker.factorAt(0) shouldBe (1.0 plusOrMinus 1e-9)
        flicker.factorAt(123) shouldBe (1.0 plusOrMinus 1e-9)
        flicker.factorAt(399) shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("a non-positive period is treated as steady (no flicker) rather than dividing by zero") {
        val flicker = LightFlicker(amplitude = 0.5, periodMs = 0)
        flicker.factorAt(100) shouldBe 1.0
    }

    test("negative elapsedMs does not throw and stays within the valid range") {
        val flicker = LightFlicker(amplitude = 0.2, periodMs = 400, seed = 10)
        val factor = flicker.factorAt(-500)
        (factor >= 0.8 - 1e-9) shouldBe true
        (factor <= 1.2 + 1e-9) shouldBe true
    }
})
