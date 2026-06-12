package com.sletmoe.korogue.utilities

import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class DirectionTest : FunSpec({

    test("every direction is a unit step, and the eight are distinct") {
        Direction.entries.forEach { dir ->
            dir.dx shouldBe dir.dx.coerceIn(-1, 1)
            dir.dy shouldBe dir.dy.coerceIn(-1, 1)
            (dir.dx != 0 || dir.dy != 0) shouldBe true
        }
        Direction.entries.map { it.dx to it.dy }.toSet().size shouldBe 8
    }

    test("opposite reverses the step") {
        Direction.entries.forEach { dir ->
            dir.opposite.dx shouldBe -dir.dx
            dir.opposite.dy shouldBe -dir.dy
        }
        Direction.NORTH.opposite shouldBe Direction.SOUTH
        Direction.NORTHEAST.opposite shouldBe Direction.SOUTHWEST
    }

    test("from steps a point one cell") {
        Direction.EAST.from(Vector2Int(5, 5)) shouldBe Vector2Int(6, 5)
        Direction.NORTHWEST.from(Vector2Int(5, 5)) shouldBe Vector2Int(4, 4)
    }

    test("cardinal and diagonal partition the eight directions") {
        Direction.CARDINAL.size shouldBe 4
        Direction.DIAGONAL.size shouldBe 4
        (Direction.CARDINAL + Direction.DIAGONAL).toSet() shouldBe Direction.entries.toSet()
        Direction.CARDINAL.all { it.dx == 0 || it.dy == 0 } shouldBe true
        Direction.DIAGONAL.all { it.dx != 0 && it.dy != 0 } shouldBe true
    }

    test("ofStep clamps an arbitrary delta to a single direction, null for no movement") {
        Direction.ofStep(5, 0) shouldBe Direction.EAST
        Direction.ofStep(-3, 9) shouldBe Direction.SOUTHWEST
        Direction.ofStep(0, 0).shouldBeNull()
    }

    test("between gives the step from origin toward target") {
        Direction.between(Vector2Int(2, 2), Vector2Int(10, 2)) shouldBe Direction.EAST
        Direction.between(Vector2Int(2, 2), Vector2Int(0, 0)) shouldBe Direction.NORTHWEST
        Direction.between(Vector2Int(2, 2), Vector2Int(2, 2)).shouldBeNull()
    }
})
