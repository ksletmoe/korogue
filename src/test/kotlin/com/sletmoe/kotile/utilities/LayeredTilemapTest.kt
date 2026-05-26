package com.sletmoe.kotile.utilities

import com.sletmoe.kotile.tiles.StaticTile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LayeredTilemapTest : FunSpec({
    test("an empty cell has no top tile") {
        LayeredTilemap(4, 4).topTileAt(0, 0) shouldBe null
    }

    test("an added tile becomes the top tile") {
        val map = LayeredTilemap(4, 4)
        val tile = StaticTile(1, 2)
        map.addTile(0, 0, 0, tile)
        map.topTileAt(0, 0) shouldBe tile
    }

    test("the highest z layer wins at a shared cell") {
        val map = LayeredTilemap(4, 4)
        val low = StaticTile(0, 0)
        val high = StaticTile(1, 1)
        map.addTile(2, 2, 0, low)
        map.addTile(2, 2, 5, high)
        map.topTileAt(2, 2) shouldBe high
    }

    test("removing the top tile exposes the tile beneath it") {
        val map = LayeredTilemap(4, 4)
        val low = StaticTile(0, 0)
        val high = StaticTile(1, 1)
        map.addTile(1, 1, 0, low)
        map.addTile(1, 1, 1, high)
        map.removeTile(1, 1, 1)
        map.topTileAt(1, 1) shouldBe low
    }

    test("removing from a layer that does not exist throws") {
        shouldThrow<IndexOutOfBoundsException> { LayeredTilemap(4, 4).removeTile(0, 0, 3) }
    }

    test("moveTile relocates a tile to another cell and layer") {
        val map = LayeredTilemap(4, 4)
        val tile = StaticTile(3, 3)
        map.addTile(0, 0, 0, tile)
        map.addTile(1, 1, 1, StaticTile(9, 9)) // make layer 1 exist
        map.moveTile(0, 0, 0, 3, 3, 1)
        map.topTileAt(0, 0) shouldBe null
        map.topTileAt(3, 3) shouldBe tile
    }

    test("dirty tiles record touched cells until marked clean") {
        val map = LayeredTilemap(4, 4)
        map.addTile(0, 0, 0, StaticTile(0, 0))
        map.addTile(3, 2, 0, StaticTile(0, 0))
        map.getDirtyTiles() shouldBe setOf(Vector2Int(0, 0), Vector2Int(3, 2))
        map.markClean()
        map.getDirtyTiles() shouldBe emptySet()
    }

    test("vector overloads delegate to the coordinate methods") {
        val map = LayeredTilemap(4, 4)
        val tile = StaticTile(1, 1)
        map.addTile(Vector3Int(2, 3, 0), tile)
        map.topTileAt(Vector2Int(2, 3)) shouldBe tile
    }
})
