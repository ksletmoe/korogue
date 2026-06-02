package com.sletmoe.krogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ExtensionsTest : DescribeSpec({

    describe("Vector2Int.plus") {
        it("should add components pairwise") {
            val a = Vector2Int(3, 5)
            val b = Vector2Int(2, 4)
            (a + b) shouldBe Vector2Int(5, 9)
        }

        it("should return the same vector when adding (0,0)") {
            val v = Vector2Int(7, 3)
            (v + Vector2Int(0, 0)) shouldBe v
        }

        it("should handle negative addend") {
            val a = Vector2Int(5, 5)
            val b = Vector2Int(-3, -2)
            (a + b) shouldBe Vector2Int(2, 3)
        }

        it("should be commutative") {
            val a = Vector2Int(1, 2)
            val b = Vector2Int(3, 4)
            (a + b) shouldBe (b + a)
        }
    }

    describe("Vector2Int.minus") {
        it("should subtract components pairwise") {
            val a = Vector2Int(10, 8)
            val b = Vector2Int(3, 5)
            (a - b) shouldBe Vector2Int(7, 3)
        }

        it("should return the same vector when subtracting (0,0)") {
            val v = Vector2Int(4, 9)
            (v - Vector2Int(0, 0)) shouldBe v
        }

        it("should return (0,0) when subtracting a vector from itself") {
            val v = Vector2Int(6, 4)
            (v - v) shouldBe Vector2Int(0, 0)
        }

        it("should produce a negative result when subtrahend is larger") {
            val a = Vector2Int(2, 3)
            val b = Vector2Int(5, 8)
            (a - b) shouldBe Vector2Int(-3, -5)
        }
    }

    describe("IntRect.translated") {
        it("should shift x and y by the given deltas, preserving width and height") {
            val rect = IntRect(1, 2, 5, 6)
            rect.translated(3, 4) shouldBe IntRect(4, 6, 5, 6)
        }

        it("should return the same rect when translated by (0, 0)") {
            val rect = IntRect(3, 7, 4, 8)
            rect.translated(0, 0) shouldBe rect
        }

        it("should handle negative translation") {
            val rect = IntRect(5, 10, 3, 3)
            rect.translated(-2, -4) shouldBe IntRect(3, 6, 3, 3)
        }

        it("should not change width or height") {
            val rect = IntRect(0, 0, 10, 20)
            val translated = rect.translated(5, 5)
            translated.width shouldBe 10
            translated.height shouldBe 20
        }
    }

    describe("IntRect.scaled") {
        it("should scale x, y, width, and height by their respective factors") {
            // IntRect(x=2, y=3, width=4, height=5).scaled(2, 3)
            // → x=2*2=4, y=3*3=9, width=4*2=8, height=5*3=15
            val rect = IntRect(2, 3, 4, 5)
            rect.scaled(2, 3) shouldBe IntRect(4, 9, 8, 15)
        }

        it("should return the same rect when scaled by (1, 1)") {
            val rect = IntRect(1, 2, 3, 4)
            rect.scaled(1, 1) shouldBe rect
        }

        it("should collapse to origin when scaled by (0, 0)") {
            val rect = IntRect(5, 10, 3, 4)
            rect.scaled(0, 0) shouldBe IntRect(0, 0, 0, 0)
        }

        it("should scale a rect at origin without changing origin position") {
            val rect = IntRect(0, 0, 6, 8)
            rect.scaled(3, 2) shouldBe IntRect(0, 0, 18, 16)
        }
    }

    describe("IntRect.lastX") {
        it("should return x + width - 1") {
            IntRect(2, 0, 5, 1).lastX shouldBe 6
        }

        it("should return x when width is 1") {
            IntRect(7, 0, 1, 1).lastX shouldBe 7
        }

        it("should work for a rect at the origin") {
            IntRect(0, 0, 10, 5).lastX shouldBe 9
        }
    }

    describe("IntRect.lastY") {
        it("should return y + height - 1") {
            IntRect(0, 3, 1, 4).lastY shouldBe 6
        }

        it("should return y when height is 1") {
            IntRect(0, 8, 1, 1).lastY shouldBe 8
        }

        it("should work for a rect at the origin") {
            IntRect(0, 0, 5, 10).lastY shouldBe 9
        }
    }
})
