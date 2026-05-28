package com.sletmoe.kotile.rendering

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [TileViewport] — no GL context required.
 */
class TileViewportTest : FunSpec({
    test("default viewport has origin at (0, 0)") {
        val vp = TileViewport()
        vp.originX shouldBe 0
        vp.originY shouldBe 0
    }

    test("viewport preserves its origin coordinates") {
        val vp = TileViewport(originX = 50, originY = 30)
        vp.originX shouldBe 50
        vp.originY shouldBe 30
    }

    test("translate shifts the origin by the given delta") {
        val vp = TileViewport(10, 5)
        val shifted = vp.translate(3, 7)
        shifted.originX shouldBe 13
        shifted.originY shouldBe 12
    }

    test("translate with negative delta moves the viewport towards the origin") {
        val vp = TileViewport(10, 10)
        val shifted = vp.translate(-4, -6)
        shifted.originX shouldBe 6
        shifted.originY shouldBe 4
    }

    test("translate does not mutate the original viewport") {
        val vp = TileViewport(10, 10)
        vp.translate(5, 5)
        vp.originX shouldBe 10
        vp.originY shouldBe 10
    }

    test("translate by zero returns an equal viewport") {
        val vp = TileViewport(7, 3)
        vp.translate(0, 0) shouldBe vp
    }

    test("data class equality holds for identical origins") {
        TileViewport(20, 15) shouldBe TileViewport(20, 15)
    }

    test("copy preserves unspecified fields") {
        val vp = TileViewport(originX = 5, originY = 8)
        vp.copy(originX = 99).originY shouldBe 8
    }
})
