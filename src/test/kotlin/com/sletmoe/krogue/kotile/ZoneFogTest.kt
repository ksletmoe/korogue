package com.sletmoe.krogue.kotile

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
})
