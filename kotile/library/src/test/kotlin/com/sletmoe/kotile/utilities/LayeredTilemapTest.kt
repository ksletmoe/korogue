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
})
