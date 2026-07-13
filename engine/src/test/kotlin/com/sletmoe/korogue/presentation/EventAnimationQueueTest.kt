package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.RecordingSurface
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [EventAnimationQueue] is the presentation-only sequencer (krogue-wuq, ADR-0023's third time
 * axis): these tests drive it with a fake [RecordingSurface]/[MapCamera] — no GL context, no ECS
 * world — since it never reads or writes game state in the first place.
 */
class EventAnimationQueueTest : FunSpec({

    val camera = MapCamera(originX = 0, originY = 0, width = 10, height = 10)

    test("idle queue plays nothing and is not playing") {
        val queue = EventAnimationQueue()
        queue.isPlaying shouldBe false
        val surface = RecordingSurface(10, 10)
        queue.update(0L)
        queue.render(surface, camera, 0L)
        surface.puts shouldBe emptyList()
    }

    test("an enqueued sequence starts playing on the next update and draws at its cell") {
        val queue = EventAnimationQueue()
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(3, 4), Color.RED, durationMs = 100L))
        queue.isPlaying shouldBe true

        queue.update(1_000L) // first update after enqueue promotes it, regardless of the clock value
        val surface = RecordingSurface(10, 10)
        queue.render(surface, camera, 1_050L) // 50ms into the 100ms flash

        val cell = surface.top(3, 4)
        cell.shouldNotBeNull()
        cell.glyph shouldBe '*'
        cell.fg shouldBe Color.RED
        cell.z shouldBe RenderLayer.OVERLAY.zIndex
    }

    test("a sequence stops playing once its duration elapses, and the next queued one takes over") {
        val queue = EventAnimationQueue()
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(1, 1), durationMs = 50L))
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(2, 2), durationMs = 50L))

        queue.update(0L) // promotes the first flash, started at t=0
        queue.isPlaying shouldBe true

        // Exactly at the first flash's duration: it completes and the second is promoted in the
        // same update, started at t=50 — no frame where nothing is playing despite more being queued.
        queue.update(50L)
        queue.isPlaying shouldBe true
        val second = RecordingSurface(10, 10)
        queue.render(second, camera, 60L) // 10ms into the second flash
        second.top(2, 2).shouldNotBeNull()
        second.top(1, 1).shouldBeNull() // the first flash is gone, no residue

        queue.update(100L) // the second flash's 50ms has now elapsed too, and nothing is queued
        queue.isPlaying shouldBe false
    }

    test("skip force-completes the head sequence immediately") {
        val queue = EventAnimationQueue()
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(0, 0), durationMs = 10_000L))
        queue.update(0L)
        queue.isPlaying shouldBe true

        queue.skip()
        queue.isPlaying shouldBe false

        val surface = RecordingSurface(10, 10)
        queue.render(surface, camera, 1L)
        surface.puts shouldBe emptyList()
    }

    test("clear drops the head sequence and everything queued behind it") {
        val queue = EventAnimationQueue()
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(0, 0), durationMs = 10_000L))
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(1, 1), durationMs = 10_000L))
        queue.update(0L)
        queue.isPlaying shouldBe true

        queue.clear()
        queue.isPlaying shouldBe false

        queue.update(1L) // nothing left to promote
        queue.isPlaying shouldBe false
    }

    test("a position outside the camera's viewport is not drawn") {
        val queue = EventAnimationQueue()
        queue.enqueue(VisualEvent.HitFlash(Vector2Int(50, 50), durationMs = 100L))
        queue.update(0L)

        val surface = RecordingSurface(10, 10)
        queue.render(surface, camera, 10L)
        surface.puts shouldBe emptyList()
    }

    test("death fade darkens toward black as it progresses") {
        val event = VisualEvent.DeathFade(Vector2Int(0, 0), 'z', Color(1f, 1f, 1f, 1f), durationMs = 100L)
        val sequence = event.toSequence()

        val start = RecordingSurface(10, 10)
        sequence.render(start, camera, 0L)
        val end = RecordingSurface(10, 10)
        sequence.render(end, camera, 99L)

        val startColor = start.top(0, 0)!!.fg
        val endColor = end.top(0, 0)!!.fg
        (endColor.r < startColor.r) shouldBe true
    }

    test("projectile interpolates linearly from its start to end cell") {
        val event = VisualEvent.Projectile(Vector2Int(0, 0), Vector2Int(4, 0), '/', durationMs = 100L)
        val sequence = event.toSequence()

        val midway = RecordingSurface(10, 10)
        sequence.render(midway, camera, 50L)
        midway.top(2, 0).shouldNotBeNull()

        val end = RecordingSurface(10, 10)
        sequence.render(end, camera, 100L)
        end.top(4, 0).shouldNotBeNull()
    }
})
