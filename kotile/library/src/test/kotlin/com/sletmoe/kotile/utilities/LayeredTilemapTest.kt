package com.sletmoe.kotile.utilities

import com.sletmoe.kotile.tiles.StaticTile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LayeredTilemapTest : FunSpec({
    test("an empty cell has no top cell") {
        LayeredTilemap<StaticTile>(4, 4).topCellAt(0, 0) shouldBe null
    }

    test("a set cell becomes the top cell") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val tile = StaticTile(1, 2)
        map.setCell(0, 0, 0, tile)
        map.topCellAt(0, 0) shouldBe tile
    }

    test("the highest z layer wins at a shared cell") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val low = StaticTile(0, 0)
        val high = StaticTile(1, 1)
        map.setCell(2, 2, 0, low)
        map.setCell(2, 2, 5, high)
        map.topCellAt(2, 2) shouldBe high
    }

    test("removing the top cell exposes the cell beneath it") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val low = StaticTile(0, 0)
        val high = StaticTile(1, 1)
        map.setCell(1, 1, 0, low)
        map.setCell(1, 1, 1, high)
        map.removeCell(1, 1, 1)
        map.topCellAt(1, 1) shouldBe low
    }

    test("removeCell from a layer that does not exist is a no-op") {
        // create-on-demand policy: removeCell on a missing layer silently does nothing
        LayeredTilemap<StaticTile>(4, 4).removeCell(0, 0, 3) // must not throw
    }

    test("moveCell relocates a cell to another position and layer") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val tile = StaticTile(3, 3)
        map.setCell(0, 0, 0, tile)
        map.moveCell(0, 0, 0, 3, 3, 1) // destination layer 1 created on demand
        map.topCellAt(0, 0) shouldBe null
        map.topCellAt(3, 3) shouldBe tile
    }

    test("moveCell from a layer that does not exist is a no-op") {
        // create-on-demand policy: moveCell with missing source layer silently does nothing
        LayeredTilemap<StaticTile>(4, 4).moveCell(0, 0, 99, 1, 1, 0) // must not throw
    }

    test("vector overloads delegate to the coordinate methods") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val tile = StaticTile(1, 1)
        map.setCell(Vector3Int(2, 3, 0), tile)
        map.topCellAt(Vector2Int(2, 3)) shouldBe tile
    }

    test("clearLayer removes all cells on a specific layer without affecting others") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val low = StaticTile(0, 0)
        val high = StaticTile(1, 1)
        map.setCell(0, 0, 0, low)
        map.setCell(0, 0, 1, high)
        map.clearLayer(1)
        map.topCellAt(0, 0) shouldBe low
    }

    test("clearLayer on a missing layer is a no-op") {
        LayeredTilemap<StaticTile>(4, 4).clearLayer(99) // must not throw
    }

    test("clearAllLayers removes every cell on every layer") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0))
        map.setCell(1, 1, 5, StaticTile(1, 1))
        map.clearAllLayers()
        map.topCellAt(0, 0) shouldBe null
        map.topCellAt(1, 1) shouldBe null
    }

    test("layerKeys reports all created layer indices") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0))
        map.setCell(0, 0, 3, StaticTile(0, 0))
        map.layerKeys shouldBe setOf(0, 3)
    }

    test("layersBottomUp yields layers from lowest z to highest for composited rendering") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val low = StaticTile(0, 0)
        val mid = StaticTile(1, 1)
        val high = StaticTile(2, 2)
        // Insert out of order to prove ordering is by z, not insertion order.
        map.setCell(1, 1, 5, high)
        map.setCell(1, 1, 0, low)
        map.setCell(1, 1, 2, mid)

        val stacked = map.layersBottomUp.map { it[1, 1] }
        stacked shouldBe listOf(low, mid, high)
    }

    // -------------------------------------------------------------------------
    // versionAt: the sole dirty signal the composite cache relies on (krogue-vn0)
    // -------------------------------------------------------------------------

    test("an unwritten cell reads version 0") {
        LayeredTilemap<StaticTile>(4, 4).versionAt(0, 0) shouldBe 0
    }

    test("setCell strictly increases the version at the written position") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val before = map.versionAt(1, 1)
        map.setCell(1, 1, 0, StaticTile(0, 0))
        (map.versionAt(1, 1) > before) shouldBe true
    }

    test("setCell does not bump the version of an untouched position") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(1, 1, 0, StaticTile(0, 0))
        map.versionAt(2, 2) shouldBe 0
    }

    test("removeCell strictly increases the version at the affected position") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(1, 1, 0, StaticTile(0, 0))
        val before = map.versionAt(1, 1)
        map.removeCell(1, 1, 0)
        (map.versionAt(1, 1) > before) shouldBe true
    }

    test("removeCell on a layer that does not exist does NOT change the version") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val before = map.versionAt(0, 0)
        map.removeCell(0, 0, 3) // layer 3 was never created
        map.versionAt(0, 0) shouldBe before
    }

    test("moveCell strictly increases the version at both the source and destination") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0))
        val fromBefore = map.versionAt(0, 0)
        val toBefore = map.versionAt(3, 3)
        map.moveCell(0, 0, 0, 3, 3, 1)
        (map.versionAt(0, 0) > fromBefore) shouldBe true
        (map.versionAt(3, 3) > toBefore) shouldBe true
    }

    test("moveCell from a layer that does not exist does NOT change either endpoint's version") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val fromBefore = map.versionAt(0, 0)
        val toBefore = map.versionAt(1, 1)
        map.moveCell(0, 0, 99, 1, 1, 0) // source layer 99 was never created
        map.versionAt(0, 0) shouldBe fromBefore
        map.versionAt(1, 1) shouldBe toBefore
    }

    test("clearLayer strictly increases the version of every position on that layer, occupied or not") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0)) // only (0,0) is actually occupied on layer 0
        val occupiedBefore = map.versionAt(0, 0)
        val emptyBefore = map.versionAt(3, 3)
        map.clearLayer(0)
        (map.versionAt(0, 0) > occupiedBefore) shouldBe true
        (map.versionAt(3, 3) > emptyBefore) shouldBe true
    }

    test("clearLayer on a missing layer does NOT change any version") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        val before = map.versionAt(2, 2)
        map.clearLayer(99) // layer 99 was never created
        map.versionAt(2, 2) shouldBe before
    }

    test("clearAllLayers strictly increases the version of every position") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0))
        val occupiedBefore = map.versionAt(0, 0)
        val emptyBefore = map.versionAt(3, 3)
        map.clearAllLayers()
        (map.versionAt(0, 0) > occupiedBefore) shouldBe true
        (map.versionAt(3, 3) > emptyBefore) shouldBe true
    }

    test("versions are globally unique so a consumer's last-seen comparison always detects a change") {
        val map = LayeredTilemap<StaticTile>(4, 4)
        map.setCell(0, 0, 0, StaticTile(0, 0))
        val v1 = map.versionAt(0, 0)
        map.setCell(1, 1, 0, StaticTile(1, 1))
        val v2 = map.versionAt(1, 1)
        map.setCell(0, 0, 0, StaticTile(2, 2)) // overwrite (0,0) again
        val v3 = map.versionAt(0, 0)

        setOf(v1, v2, v3).size shouldBe 3
    }
})
