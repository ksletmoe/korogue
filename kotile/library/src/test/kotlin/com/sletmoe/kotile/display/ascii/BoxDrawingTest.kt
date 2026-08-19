package com.sletmoe.kotile.display.ascii

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the connected-wall glyph table (krogue-k8t). Two things are worth locking
 * down: that each neighbour mask picks the junction a reader expects, and that every glyph it can
 * return is addressable as a CP437 slot — the contract [GlyphSource.glyph] indexes by. A Unicode box
 * code point would sail past the 256-glyph page and silently draw nothing, which is exactly the bug
 * this table is easy to write.
 */
class BoxDrawingTest : FunSpec({

    // The room corner cases, named from the wall's point of view: a top-left corner has neighbours to
    // its east and south, because that is where the room's edges continue.
    test("two adjacent arms make the corner that opens toward them") {
        BoxDrawing.connector(north = false, east = true, south = true, west = false) shouldBe Char(0xDA) // ┌
        BoxDrawing.connector(north = false, east = false, south = true, west = true) shouldBe Char(0xBF) // ┐
        BoxDrawing.connector(north = true, east = true, south = false, west = false) shouldBe Char(0xC0) // └
        BoxDrawing.connector(north = true, east = false, south = false, west = true) shouldBe Char(0xD9) // ┘
    }

    test("two opposite arms make a bar") {
        BoxDrawing.connector(north = false, east = true, south = false, west = true) shouldBe Char(0xC4) // ─
        BoxDrawing.connector(north = true, east = false, south = true, west = false) shouldBe Char(0xB3) // │
    }

    test("three arms make the tee pointing away from the missing one") {
        BoxDrawing.connector(north = true, east = true, south = true, west = false) shouldBe Char(0xC3) // ├
        BoxDrawing.connector(north = true, east = false, south = true, west = true) shouldBe Char(0xB4) // ┤
        BoxDrawing.connector(north = false, east = true, south = true, west = true) shouldBe Char(0xC2) // ┬
        BoxDrawing.connector(north = true, east = true, south = false, west = true) shouldBe Char(0xC1) // ┴
    }

    test("four arms make the cross") {
        BoxDrawing.connector(north = true, east = true, south = true, west = true) shouldBe Char(0xC5) // ┼
    }

    test("a single arm continues the bar it lies along, and no arm falls back to horizontal") {
        BoxDrawing.connector(north = true, east = false, south = false, west = false) shouldBe Char(0xB3)
        BoxDrawing.connector(north = false, east = false, south = true, west = false) shouldBe Char(0xB3)
        BoxDrawing.connector(north = false, east = true, south = false, west = false) shouldBe Char(0xC4)
        BoxDrawing.connector(north = false, east = false, south = false, west = true) shouldBe Char(0xC4)
        BoxDrawing.connector(north = false, east = false, south = false, west = false) shouldBe Char(0xC4)
    }

    test("the double-line alphabet mirrors the single-line one, slot for slot") {
        val double = BoxLine.DOUBLE
        BoxDrawing.connector(false, true, true, false, double) shouldBe Char(0xC9) // ╔
        BoxDrawing.connector(false, false, true, true, double) shouldBe Char(0xBB) // ╗
        BoxDrawing.connector(true, true, false, false, double) shouldBe Char(0xC8) // ╚
        BoxDrawing.connector(true, false, false, true, double) shouldBe Char(0xBC) // ╝
        BoxDrawing.connector(false, true, false, true, double) shouldBe Char(0xCD) // ═
        BoxDrawing.connector(true, false, true, false, double) shouldBe Char(0xBA) // ║
        BoxDrawing.connector(true, true, true, true, double) shouldBe Char(0xCE) // ╬
    }

    test("every glyph either alphabet can return is inside the CP437 code page") {
        for (mask in 0 until 16) {
            val n = mask and 1 != 0
            val e = mask and 2 != 0
            val s = mask and 4 != 0
            val w = mask and 8 != 0
            for (line in BoxLine.entries) {
                val code = BoxDrawing.connector(n, e, s, w, line).code
                // Addressable by a GlyphSource, and a real box-drawing slot rather than ASCII.
                (code in 0x00..0xFF) shouldBe true
                // CP437's box-drawing run: 0xB3 (│) through 0xDA (┌), which both alphabets live inside.
                (code in 0xB3..0xDA) shouldBe true
            }
        }
    }

    test("connectorAt samples the four orthogonal neighbours around the cell") {
        // A wall running along the top edge of a room: neighbours east and west are wall, nothing above.
        val wall = setOf(4 to 5, 6 to 5)
        BoxDrawing.connectorAt(5, 5) { x, y -> (x to y) in wall } shouldBe Char(0xC4) // ─

        // The same cell with a wall below instead becomes the tee that opens downward.
        val withSouth = wall + (5 to 6)
        BoxDrawing.connectorAt(5, 5) { x, y -> (x to y) in withSouth } shouldBe Char(0xC2) // ┬
    }
})
