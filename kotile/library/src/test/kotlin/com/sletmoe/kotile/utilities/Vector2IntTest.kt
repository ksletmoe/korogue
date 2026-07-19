package com.sletmoe.kotile.utilities

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Vector2IntTest : FunSpec({
    test("plus sums components") {
        Vector2Int(2, 3) + Vector2Int(4, -1) shouldBe Vector2Int(6, 2)
    }

    test("minus is the delta between two positions") {
        Vector2Int(4, 1) - Vector2Int(1, 3) shouldBe Vector2Int(3, -2)
    }

    test("a position plus the delta back to another equals that other") {
        val from = Vector2Int(5, 5)
        val to = Vector2Int(8, 2)
        from + (to - from) shouldBe to
    }
})
