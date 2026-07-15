package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.utilities.LayeredTilemap
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.kotile.utilities.Vector3Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure-logic tests for the layer compositing behaviour that backs
 * [AsciiTileWindow].
 *
 * These tests drive [LayeredTilemap]&lt;[StaticAsciiTile]&gt; directly and
 * require no OpenGL context, so they always run (no [com.sletmoe.kotile.HeadlessGl]
 * guard needed).
 *
 * End-to-end rendering with a real GL context is covered by
 * [com.sletmoe.kotile.RenderingIntegrationTest].
 */
class AsciiLayerLogicTest : FunSpec({

    fun descriptor(ch: Char) = StaticAsciiTile(ch, Color.WHITE, Color.BLACK)

    // -------------------------------------------------------------------------
    // Layer ordering / compositing
    // -------------------------------------------------------------------------

    test("an empty tilemap returns null for every cell") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        map.topCellAt(0, 0) shouldBe null
    }

    test("a cell written on z=0 becomes the top cell") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val d = descriptor('A')
        map.setCell(0, 0, 0, d)
        map.topCellAt(0, 0) shouldBe d
    }

    test("higher z wins over lower z at the same cell") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val low = descriptor('L')
        val high = descriptor('H')
        map.setCell(1, 1, 0, low)
        map.setCell(1, 1, 5, high)
        map.topCellAt(1, 1) shouldBe high
    }

    test("lower z shows through when higher z cell is null") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val base = descriptor('B')
        map.setCell(2, 2, 0, base)
        // Layer 1 exists but cell (2,2) is not set on it.
        map.setCell(0, 0, 1, descriptor('X'))
        map.topCellAt(2, 2) shouldBe base
    }

    test("negative z layers are supported and ordered correctly") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val background = descriptor('B')
        val overlay = descriptor('O')
        map.setCell(0, 0, -1, background)
        map.setCell(0, 0, 1, overlay)
        map.topCellAt(0, 0) shouldBe overlay
    }

    // -------------------------------------------------------------------------
    // removeCell permissive policy
    // -------------------------------------------------------------------------

    test("removeCell on a missing layer is a no-op") {
        // Must not throw even though layer 99 was never created.
        LayeredTilemap<StaticAsciiTile>(4, 4).removeCell(0, 0, 99)
    }

    test("removing the top cell exposes the cell on the layer below") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val base = descriptor('B')
        val top = descriptor('T')
        map.setCell(1, 1, 0, base)
        map.setCell(1, 1, 1, top)
        map.removeCell(1, 1, 1)
        map.topCellAt(1, 1) shouldBe base
    }

    test("removeCell via Vector3Int overload works") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val d = descriptor('X')
        map.setCell(Vector3Int(2, 3, 0), d)
        map.removeCell(Vector3Int(2, 3, 0))
        map.topCellAt(2, 3) shouldBe null
    }

    // -------------------------------------------------------------------------
    // clearLayer
    // -------------------------------------------------------------------------

    test("clearLayer removes all cells on one layer without disturbing others") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val base = descriptor('B')
        val overlay = descriptor('O')
        map.setCell(0, 0, 0, base)
        map.setCell(1, 1, 0, base)
        map.setCell(0, 0, 1, overlay)
        map.clearLayer(1)
        // Layer 0 is intact; layer 1 is gone.
        map.topCellAt(0, 0) shouldBe base
        map.topCellAt(1, 1) shouldBe base
    }

    test("clearLayer on a layer that does not exist is a no-op") {
        LayeredTilemap<StaticAsciiTile>(4, 4).clearLayer(42)
    }

    // -------------------------------------------------------------------------
    // clearAllLayers
    // -------------------------------------------------------------------------

    test("clearAllLayers wipes every cell on every layer") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        map.setCell(0, 0, 0, descriptor('A'))
        map.setCell(1, 1, 3, descriptor('B'))
        map.clearAllLayers()
        map.topCellAt(0, 0) shouldBe null
        map.topCellAt(1, 1) shouldBe null
    }

    // -------------------------------------------------------------------------
    // cellAt (per-layer read used during resize)
    // -------------------------------------------------------------------------

    test("cellAt returns the value on a specific layer ignoring compositing") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val base = descriptor('B')
        val top = descriptor('T')
        map.setCell(0, 0, 0, base)
        map.setCell(0, 0, 1, top)
        // topCellAt returns 'top'; cellAt returns per-layer.
        map.topCellAt(0, 0) shouldBe top
        map.cellAt(0, 0, 0) shouldBe base
        map.cellAt(0, 0, 1) shouldBe top
    }

    test("cellAt returns null for a missing layer") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        map.cellAt(0, 0, 99) shouldBe null
    }

    // -------------------------------------------------------------------------
    // layerKeys
    // -------------------------------------------------------------------------

    test("layerKeys tracks all written layers") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        map.setCell(0, 0, 0, descriptor('A'))
        map.setCell(0, 0, 2, descriptor('B'))
        map.setCell(0, 0, -1, descriptor('C'))
        map.layerKeys shouldBe setOf(0, 2, -1)
    }

    // -------------------------------------------------------------------------
    // moveCell permissive policy
    // -------------------------------------------------------------------------

    test("moveCell from a missing layer is a no-op") {
        LayeredTilemap<StaticAsciiTile>(4, 4).moveCell(0, 0, 99, 1, 1, 0)
    }

    test("moveCell relocates a cell to another position and layer") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val d = descriptor('M')
        map.setCell(0, 0, 0, d)
        map.moveCell(0, 0, 0, 3, 3, 1)
        map.topCellAt(0, 0) shouldBe null
        map.topCellAt(3, 3) shouldBe d
    }

    // -------------------------------------------------------------------------
    // Vector overloads
    // -------------------------------------------------------------------------

    test("setCell and topCellAt vector overloads work") {
        val map = LayeredTilemap<StaticAsciiTile>(4, 4)
        val d = descriptor('V')
        map.setCell(Vector3Int(1, 2, 0), d)
        map.topCellAt(Vector2Int(1, 2)) shouldBe d
    }
})
