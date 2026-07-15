package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [AnimatedAsciiTile] frame selection logic and for
 * [StaticAsciiTile.resolveAt] (which is always a no-op).
 */
class AnimatedAsciiTileTest : FunSpec({

    val dotYellow = StaticAsciiTile('.', Color.YELLOW, Color.BLACK)
    val hashGreen = StaticAsciiTile('#', Color.GREEN, Color.DARK_GRAY)
    val starRed = StaticAsciiTile('*', Color.RED, Color.WHITE)

    // -----------------------------------------------------------------------
    // StaticAsciiTile static behaviour
    // -----------------------------------------------------------------------

    test("StaticAsciiTile.resolveAt always returns itself") {
        dotYellow.resolveAt(0) shouldBe dotYellow
        dotYellow.resolveAt(999) shouldBe dotYellow
    }

    // -----------------------------------------------------------------------
    // Construction guards
    // -----------------------------------------------------------------------

    test("empty frame list throws at construction") {
        shouldThrow<IllegalArgumentException> {
            AnimatedAsciiTile(emptyList())
        }
    }

    test("frame with zero duration throws at construction") {
        shouldThrow<IllegalArgumentException> {
            AnimatedAsciiTile(
                listOf(
                    AnimationFrame(dotYellow, durationMs = 100),
                    AnimationFrame(hashGreen, durationMs = 0),
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // LOOP playback
    // -----------------------------------------------------------------------

    test("LOOP: returns first frame at elapsedMs = 0") {
        val tile = AnimatedAsciiTile(
            listOf(
                AnimationFrame(dotYellow, durationMs = 120),
                AnimationFrame(hashGreen, durationMs = 80),
            ),
        )
        tile.resolveAt(0) shouldBe dotYellow
    }

    test("LOOP: advances at frame boundary") {
        val tile = AnimatedAsciiTile(
            listOf(
                AnimationFrame(dotYellow, durationMs = 120),
                AnimationFrame(hashGreen, durationMs = 80),
            ),
        )
        tile.resolveAt(119) shouldBe dotYellow
        tile.resolveAt(120) shouldBe hashGreen
    }

    test("LOOP: wraps after total duration") {
        val tile = AnimatedAsciiTile(
            listOf(
                AnimationFrame(dotYellow, durationMs = 120),
                AnimationFrame(hashGreen, durationMs = 80),
            ),
        )
        // Total = 200 ms
        tile.resolveAt(200) shouldBe dotYellow
        tile.resolveAt(319) shouldBe dotYellow
        tile.resolveAt(320) shouldBe hashGreen
    }

    // -----------------------------------------------------------------------
    // Brogue-style flicker with PING_PONG
    // -----------------------------------------------------------------------

    test("PING_PONG: oscillates across three flicker frames") {
        val dark = StaticAsciiTile('.', Color.YELLOW, Color(0.4f, 0.2f, 0f, 1f))
        val mid = StaticAsciiTile('.', Color.YELLOW, Color(0.5f, 0.25f, 0f, 1f))
        val bright = StaticAsciiTile('.', Color.ORANGE, Color(0.55f, 0.28f, 0f, 1f))

        val tile = AnimatedAsciiTile(
            listOf(
                AnimationFrame(dark, durationMs = 100),
                AnimationFrame(mid, durationMs = 100),
                AnimationFrame(bright, durationMs = 100),
            ),
            mode = PlaybackMode.PING_PONG,
        )
        // Forward: dark(0-99) mid(100-199) bright(200-299)
        // Backward: mid(300-399)  → period = 400
        tile.resolveAt(0) shouldBe dark
        tile.resolveAt(100) shouldBe mid
        tile.resolveAt(200) shouldBe bright
        tile.resolveAt(300) shouldBe mid
        tile.resolveAt(400) shouldBe dark  // wrap
    }

    // -----------------------------------------------------------------------
    // ONCE playback
    // -----------------------------------------------------------------------

    test("ONCE: holds last frame after sequence ends") {
        val tile = AnimatedAsciiTile(
            listOf(
                AnimationFrame(dotYellow, durationMs = 100),
                AnimationFrame(hashGreen, durationMs = 100),
                AnimationFrame(starRed, durationMs = 100),
            ),
            mode = PlaybackMode.ONCE,
        )
        tile.resolveAt(0) shouldBe dotYellow
        tile.resolveAt(200) shouldBe starRed
        tile.resolveAt(300) shouldBe starRed     // past end: hold last
        tile.resolveAt(99_999) shouldBe starRed
    }
})
