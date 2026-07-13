package com.sletmoe.korogue.algorithms.geometry

import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BresenhamLineTest : FunSpec({

    test("a zero-length line returns a single cell") {
        bresenhamLine(Vector2Int(3, 3), Vector2Int(3, 3)) shouldBe listOf(Vector2Int(3, 3))
    }

    test("a horizontal line steps one cell at a time, endpoints inclusive") {
        bresenhamLine(Vector2Int(0, 0), Vector2Int(4, 0)) shouldBe
            listOf(Vector2Int(0, 0), Vector2Int(1, 0), Vector2Int(2, 0), Vector2Int(3, 0), Vector2Int(4, 0))
    }

    test("a vertical line steps one cell at a time, endpoints inclusive") {
        bresenhamLine(Vector2Int(2, 0), Vector2Int(2, 3)) shouldBe
            listOf(Vector2Int(2, 0), Vector2Int(2, 1), Vector2Int(2, 2), Vector2Int(2, 3))
    }

    test("a perfect diagonal steps one cell at a time, endpoints inclusive") {
        bresenhamLine(Vector2Int(0, 0), Vector2Int(3, 3)) shouldBe
            listOf(Vector2Int(0, 0), Vector2Int(1, 1), Vector2Int(2, 2), Vector2Int(3, 3))
    }

    test("works walking backwards (to is left of/above from)") {
        bresenhamLine(Vector2Int(4, 0), Vector2Int(0, 0)) shouldBe
            listOf(Vector2Int(4, 0), Vector2Int(3, 0), Vector2Int(2, 0), Vector2Int(1, 0), Vector2Int(0, 0))
    }

    test("a shallow non-axis-aligned line starts at from and ends at to") {
        val line = bresenhamLine(Vector2Int(0, 0), Vector2Int(7, 2))
        line.first() shouldBe Vector2Int(0, 0)
        line.last() shouldBe Vector2Int(7, 2)
    }

    test("a shallow non-axis-aligned line never skips more than one cell per axis per step") {
        val line = bresenhamLine(Vector2Int(0, 0), Vector2Int(7, 2))
        for (i in 1 until line.size) {
            val dx = kotlin.math.abs(line[i].x - line[i - 1].x)
            val dy = kotlin.math.abs(line[i].y - line[i - 1].y)
            (dx <= 1 && dy <= 1) shouldBe true
        }
    }

    test("lineOfCellsStoppingAtBlocker returns the full line when nothing blocks it") {
        lineOfCellsStoppingAtBlocker(Vector2Int(0, 0), Vector2Int(4, 0)) { false } shouldBe
            bresenhamLine(Vector2Int(0, 0), Vector2Int(4, 0))
    }

    test("lineOfCellsStoppingAtBlocker truncates at (and includes) the first blocked cell") {
        val result =
            lineOfCellsStoppingAtBlocker(Vector2Int(0, 0), Vector2Int(4, 0)) { it == Vector2Int(2, 0) }
        result shouldBe listOf(Vector2Int(0, 0), Vector2Int(1, 0), Vector2Int(2, 0))
    }

    test("lineOfCellsStoppingAtBlocker never treats the origin cell itself as a blocker") {
        val result =
            lineOfCellsStoppingAtBlocker(Vector2Int(0, 0), Vector2Int(4, 0)) { it == Vector2Int(0, 0) }
        result shouldBe bresenhamLine(Vector2Int(0, 0), Vector2Int(4, 0))
    }

    test("lineOfCellsStoppingAtBlocker stops immediately when the very next cell is blocked") {
        val result =
            lineOfCellsStoppingAtBlocker(Vector2Int(0, 0), Vector2Int(4, 0)) { it == Vector2Int(1, 0) }
        result shouldBe listOf(Vector2Int(0, 0), Vector2Int(1, 0))
    }
})
