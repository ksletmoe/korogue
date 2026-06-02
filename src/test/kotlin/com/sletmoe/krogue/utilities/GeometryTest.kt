package com.sletmoe.krogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class GeometryTest : DescribeSpec({

    val dEps = 1e-9

    describe("Vector2Int.distanceSq") {
        it("should return 0.0 for the same point") {
            val p = Vector2Int(3, 4)
            p.distanceSq(p) shouldBe (0.0 plusOrMinus dEps)
        }

        it("should return 0.0 for two equal points") {
            Vector2Int(5, 7).distanceSq(Vector2Int(5, 7)) shouldBe (0.0 plusOrMinus dEps)
        }

        it("should return 25.0 for a 3-4-5 right triangle") {
            // (0,0) to (3,4): dx=3,dy=4 → 9+16=25
            Vector2Int(0, 0).distanceSq(Vector2Int(3, 4)) shouldBe (25.0 plusOrMinus dEps)
        }

        it("should be commutative") {
            val a = Vector2Int(1, 2)
            val b = Vector2Int(4, 6)
            a.distanceSq(b) shouldBe (b.distanceSq(a) plusOrMinus dEps)
        }

        it("should return 1.0 for orthogonal neighbors") {
            Vector2Int(0, 0).distanceSq(Vector2Int(1, 0)) shouldBe (1.0 plusOrMinus dEps)
            Vector2Int(0, 0).distanceSq(Vector2Int(0, 1)) shouldBe (1.0 plusOrMinus dEps)
        }

        it("should return 2.0 for diagonal neighbors") {
            Vector2Int(0, 0).distanceSq(Vector2Int(1, 1)) shouldBe (2.0 plusOrMinus dEps)
        }

        it("should work with negative coordinates") {
            // (-3,-4) to (0,0): dx=3, dy=4 → 25
            Vector2Int(-3, -4).distanceSq(Vector2Int(0, 0)) shouldBe (25.0 plusOrMinus dEps)
        }
    }

    describe("Vector2Int.distance") {
        it("should return 0.0 for the same point") {
            val p = Vector2Int(2, 2)
            p.distance(p) shouldBe (0.0 plusOrMinus dEps)
        }

        it("should return 5.0 for a 3-4-5 right triangle") {
            Vector2Int(0, 0).distance(Vector2Int(3, 4)) shouldBe (5.0 plusOrMinus dEps)
        }

        it("should be commutative") {
            val a = Vector2Int(1, 1)
            val b = Vector2Int(4, 5)
            a.distance(b) shouldBe (b.distance(a) plusOrMinus dEps)
        }

        it("should return 1.0 for orthogonal neighbors") {
            Vector2Int(0, 0).distance(Vector2Int(1, 0)) shouldBe (1.0 plusOrMinus dEps)
        }
    }

    describe("IntRect.contains") {
        // IntRect(x=0, y=0, width=3, height=3) covers [0,3) x [0,3) — i.e. 0,1,2 in each axis

        it("should contain the top-left corner (inclusive lower bound)") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(0, 0)) shouldBe true
        }

        it("should contain an interior point") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(1, 1)) shouldBe true
        }

        it("should contain (2,2), the last valid point for a 3x3 rect at origin") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(2, 2)) shouldBe true
        }

        it("should NOT contain (3,3) because the high edge is exclusive") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(3, 3)) shouldBe false
        }

        it("should NOT contain (3,0) because x=3 equals x+width and is exclusive") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(3, 0)) shouldBe false
        }

        it("should NOT contain (0,3) because y=3 equals y+height and is exclusive") {
            IntRect(0, 0, 3, 3).contains(Vector2Int(0, 3)) shouldBe false
        }

        it("should NOT contain a point left of the rect") {
            IntRect(1, 1, 3, 3).contains(Vector2Int(0, 2)) shouldBe false
        }

        it("should NOT contain a point above the rect") {
            IntRect(1, 1, 3, 3).contains(Vector2Int(2, 0)) shouldBe false
        }

        it("should contain the rect's origin when rect has non-zero offset") {
            IntRect(5, 10, 4, 4).contains(Vector2Int(5, 10)) shouldBe true
        }

        it("should NOT contain the point one past the right edge when rect has non-zero offset") {
            // x+width = 5+4 = 9, so x=9 is exclusive
            IntRect(5, 10, 4, 4).contains(Vector2Int(9, 10)) shouldBe false
        }

        it("should contain the last valid column of a non-zero-offset rect") {
            // last valid x = 5+4-1 = 8
            IntRect(5, 10, 4, 4).contains(Vector2Int(8, 10)) shouldBe true
        }
    }
})
