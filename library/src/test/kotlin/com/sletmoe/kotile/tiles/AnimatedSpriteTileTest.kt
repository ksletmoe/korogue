package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.g2d.TextureRegion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [AnimatedSpriteTile] frame selection logic.
 *
 * These tests avoid any GPU state — [TextureRegion] objects are constructed
 * without a backing [com.badlogic.gdx.graphics.Texture] so no GL context is
 * required.
 */
class AnimatedSpriteTileTest : FunSpec({

    // -----------------------------------------------------------------------
    // Construction guards
    // -----------------------------------------------------------------------

    test("empty frame list throws at construction") {
        shouldThrow<IllegalArgumentException> {
            AnimatedSpriteTile(emptyList())
        }
    }

    test("frame with zero duration throws at construction") {
        shouldThrow<IllegalArgumentException> {
            AnimatedSpriteTile(
                listOf(
                    AnimationFrame(TextureRegion(), durationMs = 100),
                    AnimationFrame(TextureRegion(), durationMs = 0),
                ),
            )
        }
    }

    test("frame with negative duration throws at construction") {
        shouldThrow<IllegalArgumentException> {
            AnimatedSpriteTile(
                listOf(AnimationFrame(TextureRegion(), durationMs = -1)),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Single-frame edge case
    // -----------------------------------------------------------------------

    test("single frame is always returned regardless of elapsed time") {
        val region = TextureRegion()
        val tile = AnimatedSpriteTile(listOf(AnimationFrame(region, durationMs = 100)))
        tile.regionFor(0) shouldBe region
        tile.regionFor(99) shouldBe region
        tile.regionFor(10_000) shouldBe region
    }

    // -----------------------------------------------------------------------
    // LOOP playback
    // -----------------------------------------------------------------------

    test("LOOP: returns frame 0 at elapsedMs = 0") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 100),
            ),
            mode = PlaybackMode.LOOP,
        )
        tile.regionFor(0) shouldBe r0
    }

    test("LOOP: advances to frame 1 at exactly the first frame boundary") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 200),
            ),
            mode = PlaybackMode.LOOP,
        )
        tile.regionFor(99) shouldBe r0
        tile.regionFor(100) shouldBe r1
    }

    test("LOOP: wraps back to frame 0 after the full sequence") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val r2 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 100),
                AnimationFrame(r2, durationMs = 100),
            ),
            mode = PlaybackMode.LOOP,
        )
        // Total duration = 300 ms. At 300 ms we wrap back to frame 0.
        tile.regionFor(300) shouldBe r0
        tile.regionFor(350) shouldBe r1
        tile.regionFor(599) shouldBe r2
        tile.regionFor(600) shouldBe r0
    }

    test("LOOP: works correctly at known elapsed time with unequal frame durations") {
        val frames = listOf(
            AnimationFrame(TextureRegion(), durationMs = 200L),
            AnimationFrame(TextureRegion(), durationMs = 50L),
            AnimationFrame(TextureRegion(), durationMs = 150L),
        )
        val tile = AnimatedSpriteTile(frames, mode = PlaybackMode.LOOP)
        // Total = 400 ms
        // Frame 0: [0, 200), Frame 1: [200, 250), Frame 2: [250, 400)
        tile.regionFor(0) shouldBe frames[0].content
        tile.regionFor(199) shouldBe frames[0].content
        tile.regionFor(200) shouldBe frames[1].content
        tile.regionFor(249) shouldBe frames[1].content
        tile.regionFor(250) shouldBe frames[2].content
        tile.regionFor(399) shouldBe frames[2].content
        tile.regionFor(400) shouldBe frames[0].content   // wrap: 400 % 400 = 0 → frame 0
        tile.regionFor(450) shouldBe frames[0].content   // 450 % 400 = 50 → still in frame 0 ([0, 200))
    }

    // -----------------------------------------------------------------------
    // ONCE playback
    // -----------------------------------------------------------------------

    test("ONCE: plays frames forward and holds last frame after sequence ends") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val r2 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 100),
                AnimationFrame(r2, durationMs = 100),
            ),
            mode = PlaybackMode.ONCE,
        )
        tile.regionFor(0) shouldBe r0
        tile.regionFor(100) shouldBe r1
        tile.regionFor(200) shouldBe r2
        tile.regionFor(299) shouldBe r2
        tile.regionFor(300) shouldBe r2   // past end: hold last frame
        tile.regionFor(10_000) shouldBe r2
    }

    // -----------------------------------------------------------------------
    // PING_PONG playback
    // -----------------------------------------------------------------------

    test("PING_PONG: goes forward then backward through frames") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val r2 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 100),
                AnimationFrame(r2, durationMs = 100),
            ),
            mode = PlaybackMode.PING_PONG,
        )
        // Forward: A(0-99) B(100-199) C(200-299)
        // Backward: B(300-399) A(400-499)
        // Period = 2 * (300 - 100) = 400 ms  (last frame not duplicated)
        tile.regionFor(0) shouldBe r0
        tile.regionFor(100) shouldBe r1
        tile.regionFor(200) shouldBe r2
        tile.regionFor(300) shouldBe r1   // reverse: B
        tile.regionFor(400) shouldBe r0   // reverse: A → wrap
        tile.regionFor(400) shouldBe r0
    }

    test("PING_PONG: single frame behaves like LOOP") {
        val r0 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(AnimationFrame(r0, durationMs = 100)),
            mode = PlaybackMode.PING_PONG,
        )
        tile.regionFor(0) shouldBe r0
        tile.regionFor(99) shouldBe r0
        tile.regionFor(1000) shouldBe r0
    }

    // -----------------------------------------------------------------------
    // Negative elapsed time
    // -----------------------------------------------------------------------

    test("negative elapsed time is clamped to first frame") {
        val r0 = TextureRegion()
        val r1 = TextureRegion()
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(r0, durationMs = 100),
                AnimationFrame(r1, durationMs = 100),
            ),
        )
        tile.regionFor(-1) shouldBe r0
        tile.regionFor(-9999) shouldBe r0
    }

    // -----------------------------------------------------------------------
    // Tint default
    // -----------------------------------------------------------------------

    test("tint defaults to Color.WHITE") {
        val tile = AnimatedSpriteTile(listOf(AnimationFrame(TextureRegion(), durationMs = 100)))
        tile.tint shouldBe com.badlogic.gdx.graphics.Color.WHITE
    }
})
