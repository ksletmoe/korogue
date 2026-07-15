package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [AnimatedSpriteTile] frame selection logic.
 *
 * These tests avoid any GPU state — [TextureRegion] objects are constructed
 * without a backing [com.badlogic.gdx.graphics.Texture] so no GL context is
 * required.
 */
class AnimatedSpriteTileTest : FunSpec({

    // -----------------------------------------------------------------------
    // Branch membership (krogue-xcx). TileRenderer decides which cells to
    // recomposite every frame with `is DynamicSpriteTile`; re-parenting this
    // class straight onto SpriteTile would freeze animation on its first
    // painted frame with nothing else failing. Mirrors the ASCII path's
    // [com.sletmoe.kotile.display.ascii.DynamicAsciiTileTest].
    // -----------------------------------------------------------------------

    test("AnimatedSpriteTile is a DynamicSpriteTile, so animated cells get per-frame recomposites") {
        val tile = AnimatedSpriteTile(listOf(AnimationFrame(TextureRegion(), durationMs = 100)))

        tile.shouldBeInstanceOf<DynamicSpriteTile>()
    }

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
        // Total duration = 300 ms. At 300 ms we wrap back to frame 0; 350 = 50 into frame 0.
        tile.regionFor(300) shouldBe r0
        tile.regionFor(350) shouldBe r0
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
        tile.tint shouldBe Color.WHITE
    }

    // -----------------------------------------------------------------------
    // Per-frame tint (krogue-2ur)
    // -----------------------------------------------------------------------

    test("tintFor defaults to the tile's constant tint when no frame overrides it") {
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(TextureRegion(), durationMs = 100),
                AnimationFrame(TextureRegion(), durationMs = 100),
            ),
            tint = Color.RED,
        )
        tile.tintFor(0) shouldBe Color.RED
        tile.tintFor(150) shouldBe Color.RED
    }

    test("a frame's tint overrides the constant tint when the tile tint is WHITE") {
        val shimmer = Color(0.5f, 0.5f, 1f, 1f)
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(TextureRegion(), durationMs = 100, tint = Color.WHITE),
                AnimationFrame(TextureRegion(), durationMs = 100, tint = shimmer),
            ),
        )
        tile.tintFor(0) shouldBe Color.WHITE
        tile.tintFor(150) shouldBe shimmer
    }

    test("a frame's tint multiplies with a non-white constant tint") {
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(TextureRegion(), durationMs = 100, tint = Color(0.5f, 1f, 1f, 1f)),
            ),
            tint = Color(1f, 0.5f, 1f, 1f),
        )
        // (0.5, 1, 1, 1) * (1, 0.5, 1, 1) = (0.5, 0.5, 1, 1)
        val effective = tile.tintFor(0)
        effective.r shouldBe 0.5f
        effective.g shouldBe 0.5f
        effective.b shouldBe 1f
        effective.a shouldBe 1f
    }

    test("mixed frames: only the overriding frame changes color, others still show constant tint") {
        val shimmer = Color(0.2f, 0.9f, 0.9f, 1f)
        val tile = AnimatedSpriteTile(
            listOf(
                AnimationFrame(TextureRegion(), durationMs = 100),
                AnimationFrame(TextureRegion(), durationMs = 100, tint = shimmer),
                AnimationFrame(TextureRegion(), durationMs = 100),
            ),
            tint = Color.WHITE,
        )
        tile.tintFor(0) shouldBe Color.WHITE
        tile.tintFor(150) shouldBe shimmer
        tile.tintFor(250) shouldBe Color.WHITE
    }

    test("tintFor does not mutate the tile's own tint or the frame's tint") {
        val frameTint = Color(0.3f, 0.4f, 0.5f, 1f)
        val tileTint = Color(0.6f, 0.7f, 0.8f, 1f)
        val tile = AnimatedSpriteTile(
            listOf(AnimationFrame(TextureRegion(), durationMs = 100, tint = frameTint)),
            tint = tileTint,
        )
        tile.tintFor(0)
        tile.tint shouldBe tileTint
        tile.frames[0].tint shouldBe frameTint
    }

    // -----------------------------------------------------------------------
    // flipX / flipY (krogue-csc)
    // -----------------------------------------------------------------------

    test("flipX and flipY default to false") {
        val tile = AnimatedSpriteTile(listOf(AnimationFrame(TextureRegion(), durationMs = 100)))
        tile.flipX shouldBe false
        tile.flipY shouldBe false
    }

    test("an explicit flipX/flipY is retained") {
        val tile = AnimatedSpriteTile(
            listOf(AnimationFrame(TextureRegion(), durationMs = 100)),
            flipX = true,
            flipY = true,
        )
        tile.flipX shouldBe true
        tile.flipY shouldBe true
    }
})
