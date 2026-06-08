package com.sletmoe.krogue.ui

import com.sletmoe.krogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LayoutTest : FunSpec({

    val rect = IntRect(0, 0, 80, 40)

    test("splitTop takes the top band and leaves the rest below") {
        val (top, bottom) = rect.splitTop(3)
        top shouldBe IntRect(0, 0, 80, 3)
        bottom shouldBe IntRect(0, 3, 80, 37)
    }

    test("splitBottom takes the bottom band and leaves the rest above") {
        val (top, bottom) = rect.splitBottom(3)
        top shouldBe IntRect(0, 0, 80, 37)
        bottom shouldBe IntRect(0, 37, 80, 3)
    }

    test("splitLeft / splitRight cut vertical bands") {
        rect.splitLeft(20).left shouldBe IntRect(0, 0, 20, 40)
        rect.splitLeft(20).right shouldBe IntRect(20, 0, 60, 40)
        rect.splitRight(20).left shouldBe IntRect(0, 0, 60, 40)
        rect.splitRight(20).right shouldBe IntRect(60, 0, 20, 40)
    }

    test("splits are offset-aware (not assuming origin 0,0)") {
        val (left, right) = IntRect(10, 5, 30, 20).splitRight(10)
        left shouldBe IntRect(10, 5, 20, 20)
        right shouldBe IntRect(30, 5, 10, 20)
    }

    test("over-asking clamps to the rect rather than producing a negative size") {
        val (top, bottom) = rect.splitTop(100)
        top shouldBe IntRect(0, 0, 80, 40)
        bottom shouldBe IntRect(0, 40, 80, 0)
    }

    test("inset shrinks on every side and clamps at zero") {
        IntRect(2, 2, 10, 8).inset(1) shouldBe IntRect(3, 3, 8, 6)
        IntRect(0, 0, 3, 3).inset(5) shouldBe IntRect(5, 5, 0, 0)
    }
})
