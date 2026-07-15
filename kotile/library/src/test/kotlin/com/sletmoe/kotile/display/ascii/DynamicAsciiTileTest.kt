package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.utilities.LayeredTilemap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for the consumer-implementable [DynamicAsciiTile] branch (krogue-xcx) —
 * the ASCII counterpart of [com.sletmoe.kotile.tiles.DynamicSpriteTile].
 *
 * These are pure-logic tests requiring no OpenGL context. The end-to-end
 * "a custom dynamic tile actually keeps repainting" check needs real pixels and
 * lives in [com.sletmoe.kotile.RenderingIntegrationTest].
 */
class DynamicAsciiTileTest : FunSpec({

    /**
     * A dynamic tile driven by an arbitrary rule rather than a frame list — the
     * thing the open branch exists to allow. Alternates glyph by whole seconds.
     */
    class BlinkingCursor : DynamicAsciiTile {
        override fun resolveAt(elapsedMs: Long): StaticAsciiTile =
            if ((elapsedMs / 1000) % 2 == 0L) {
                StaticAsciiTile('_', Color.WHITE, Color.BLACK)
            } else {
                StaticAsciiTile(' ', Color.WHITE, Color.BLACK)
            }
    }

    test("a consumer-supplied DynamicAsciiTile resolves through the AsciiTile base") {
        val cursor: AsciiTile = BlinkingCursor()

        cursor.resolveAt(0).character shouldBe '_'
        cursor.resolveAt(999).character shouldBe '_'
        cursor.resolveAt(1_000).character shouldBe ' '
        cursor.resolveAt(2_000).character shouldBe '_'
    }

    // -------------------------------------------------------------------------
    // Branch membership. AsciiTileWindow decides which cells to repaint every
    // frame with `is DynamicAsciiTile` (and which to leave on the static
    // composite cache). These assertions pin that contract: re-parenting
    // AnimatedAsciiTile straight onto AsciiTile, or letting StaticAsciiTile into
    // the dynamic branch, would silently freeze animation or defeat the cache
    // with nothing else failing.
    // -------------------------------------------------------------------------

    test("AnimatedAsciiTile is a DynamicAsciiTile, so animated cells get per-frame repaints") {
        val animated = AnimatedAsciiTile(
            frames = listOf(
                AnimationFrame(StaticAsciiTile('.', Color.YELLOW, Color.BLACK), durationMs = 100),
                AnimationFrame(StaticAsciiTile('#', Color.GREEN, Color.BLACK), durationMs = 100),
            ),
        )

        animated.shouldBeInstanceOf<DynamicAsciiTile>()
    }

    test("StaticAsciiTile is not a DynamicAsciiTile, so static cells stay on the cached path") {
        val static: AsciiTile = StaticAsciiTile('@', Color.WHITE, Color.BLACK)

        (static is DynamicAsciiTile) shouldBe false
    }

    test("a tilemap of AsciiTile holds both branches, and the dynamic ones are distinguishable") {
        // Mirrors how AsciiTileWindow scans its layers to find the cells needing a repaint.
        val map = LayeredTilemap<AsciiTile>(4, 4)
        map.setCell(0, 0, 0, StaticAsciiTile('#', Color.WHITE, Color.BLACK))
        map.setCell(1, 0, 0, BlinkingCursor())

        (map.topCellAt(0, 0) is DynamicAsciiTile) shouldBe false
        (map.topCellAt(1, 0) is DynamicAsciiTile) shouldBe true
    }
})
