package com.sletmoe.korogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.tiles.AnimationFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TileSurfaceTest : FunSpec({

    test("text writes one character per cell to the right") {
        val surface = RecordingSurface(20, 5)
        surface.text(2, 1, z = 3, text = "Hi", fg = Color.WHITE)

        surface.puts.map { Triple(it.x, it.y, it.glyph) } shouldBe
            listOf(Triple(2, 1, 'H'), Triple(3, 1, 'i'))
        surface.puts.all { it.z == 3 } shouldBe true
    }

    test("fill covers every cell on the given layer") {
        val surface = RecordingSurface(4, 3)
        surface.fill(z = 0, glyph = ' ', fg = Color.WHITE, bg = Color.DARK_GRAY)

        surface.puts.size shouldBe 12 // 4 * 3
        surface.top(3, 2)!!.bg shouldBe Color.DARK_GRAY
    }

    test("the AsciiTile overload defaults to forwarding the tile's first frame as a static cell") {
        // A surface that only implements the glyph/fg/bg primitive inherits the default put(tile),
        // which must reduce a dynamic tile to resolveAt(0) so a clockless sink still draws something.
        val surface = StaticOnlySurface(10, 10)
        val flicker =
            AnimatedAsciiTile(
                frames =
                    listOf(
                        AnimationFrame(StaticAsciiTile('.', Color.RED, Color.BLACK), 100),
                        AnimationFrame(StaticAsciiTile('.', Color.BLUE, Color.BLACK), 100),
                    ),
            )

        surface.put(2, 3, z = 1, tile = flicker)

        surface.puts.single() shouldBe StaticOnlySurface.Put(2, 3, 1, '.', Color.RED, Color.BLACK)
    }
})

/** A [TileSurface] that implements only the glyph/fg/bg primitive, to exercise the inherited default put(tile). */
private class StaticOnlySurface(
    override val width: Int,
    override val height: Int,
) : TileSurface {
    data class Put(val x: Int, val y: Int, val z: Int, val glyph: Char, val fg: Color, val bg: Color)

    val puts = mutableListOf<Put>()

    override fun put(
        x: Int,
        y: Int,
        z: Int,
        glyph: Char,
        fg: Color,
        bg: Color,
    ) {
        puts.add(Put(x, y, z, glyph, fg, bg))
    }
}
