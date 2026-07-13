package com.sletmoe.kotile.rendering

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class RotationTowardsTest : FunSpec({
    test("a rightward velocity points at 0 degrees") {
        rotationTowards(1f, 0f) shouldBe (0f plusOrMinus 1e-3f)
    }

    test("a downward velocity (content-pixel +Y) points at 90 degrees") {
        rotationTowards(0f, 1f) shouldBe (90f plusOrMinus 1e-3f)
    }

    test("an upward velocity points at -90 degrees") {
        rotationTowards(0f, -1f) shouldBe (-90f plusOrMinus 1e-3f)
    }

    test("a leftward velocity points at 180 degrees") {
        rotationTowards(-1f, 0f) shouldBe (180f plusOrMinus 1e-3f)
    }

    test("magnitude does not affect the angle") {
        rotationTowards(3f, 3f) shouldBe (rotationTowards(30f, 30f) plusOrMinus 1e-3f)
    }

    test("a zero vector yields 0 rather than an undefined angle") {
        rotationTowards(0f, 0f) shouldBe (0f plusOrMinus 1e-3f)
    }
})
