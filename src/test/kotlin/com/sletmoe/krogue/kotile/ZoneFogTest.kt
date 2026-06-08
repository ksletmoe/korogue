package com.sletmoe.krogue.kotile

import com.sletmoe.krogue.utilities.Grid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

class ZoneFogTest : FunSpec({

    test("returns the same grid for a zone across calls, so exploration accumulates") {
        val fog = ZoneFog()
        val first = fog.forZone("a", 4, 4)
        first[1, 2] = true // mark a cell explored

        val again = fog.forZone("a", 4, 4)

        again shouldBeSameInstanceAs first
        again[1, 2] shouldBe true // remembered, not reset
    }

    test("gives each zone its own independent grid") {
        val fog = ZoneFog()
        val a = fog.forZone("a", 4, 4)
        val b = fog.forZone("b", 4, 4)

        a shouldNotBeSameInstanceAs b
        a[0, 0] = true
        b[0, 0] shouldBe false // zones don't share memory
    }

    test("creates a fully-unexplored grid sized to the zone") {
        val fog = ZoneFog()
        val grid = fog.forZone("a", 3, 5)

        grid.width shouldBe 3
        grid.height shouldBe 5
        grid[2, 4] shouldBe false
    }

    test("snapshot copies the grids, decoupled from later live mutation") {
        val fog = ZoneFog()
        fog.forZone("a", 4, 4)[1, 1] = true

        val snap = fog.snapshot()
        fog.forZone("a", 4, 4)[2, 2] = true // mutate the live grid after snapshotting

        snap.getValue("a")[1, 1] shouldBe true
        snap.getValue("a")[2, 2] shouldBe false // snapshot is not affected by later changes
        snap.getValue("a") shouldNotBeSameInstanceAs fog.forZone("a", 4, 4)
    }

    test("restore replaces memory with copies of the given grids") {
        val fog = ZoneFog()
        fog.forZone("a", 4, 4)[0, 0] = true // pre-existing memory, should be replaced
        val source = Grid(4, 4, false).apply { this[3, 3] = true }

        fog.restore(mapOf("b" to source))

        fog.forZone("a", 4, 4)[0, 0] shouldBe false // old "a" cleared
        fog.forZone("b", 4, 4)[3, 3] shouldBe true // "b" restored
        source[1, 1] = true // mutating the source must not leak into the restored copy
        fog.forZone("b", 4, 4)[1, 1] shouldBe false
    }
})
