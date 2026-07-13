package com.sletmoe.korogue.presentation

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.RecordingSurface
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [VisualEffectPool] is the non-gating companion to [EventAnimationQueue]: no GL context or ECS
 * world needed, same as that queue's own tests — driven with a fake [RecordingSurface]/[MapCamera].
 */
class VisualEffectPoolTest : FunSpec({

    val camera = MapCamera(originX = 0, originY = 0, width = 10, height = 10)

    test("an idle pool plays nothing") {
        val pool = VisualEffectPool()
        pool.isPlaying shouldBe false
        pool.activeEvents shouldBe emptyList()
        val surface = RecordingSurface(10, 10)
        pool.render(surface, camera, 0L)
        surface.puts shouldBe emptyList()
    }

    test("two effects spawned at once both play simultaneously, unlike EventAnimationQueue's one-at-a-time") {
        val pool = VisualEffectPool()
        pool.spawn(VisualEvent.HitFlash(Vector2Int(1, 1), Color.RED, durationMs = 100L), nowMs = 0L)
        pool.spawn(VisualEvent.HitFlash(Vector2Int(5, 5), Color.BLUE, durationMs = 100L), nowMs = 0L)

        pool.update(50L)
        val surface = RecordingSurface(10, 10)
        pool.render(surface, camera, 50L)

        surface.top(1, 1).shouldNotBeNull()
        surface.top(5, 5).shouldNotBeNull()
    }

    test("each effect expires independently based on its own spawn time and duration") {
        val pool = VisualEffectPool()
        pool.spawn(VisualEvent.HitFlash(Vector2Int(1, 1), durationMs = 50L), nowMs = 0L)
        pool.spawn(VisualEvent.HitFlash(Vector2Int(2, 2), durationMs = 200L), nowMs = 0L)

        pool.update(60L) // the first has expired, the second has not
        pool.isPlaying shouldBe true
        pool.activeEvents.size shouldBe 1

        val surface = RecordingSurface(10, 10)
        pool.render(surface, camera, 60L)
        surface.top(1, 1) shouldBe null
        surface.top(2, 2).shouldNotBeNull()

        pool.update(300L) // both now expired
        pool.isPlaying shouldBe false
    }

    test("activeEvents exposes the original VisualEvent so a caller can find one at a specific cell") {
        val pool = VisualEffectPool()
        val flash = VisualEvent.HitFlash(Vector2Int(3, 3), Color.GREEN, durationMs = 100L)
        pool.spawn(flash, nowMs = 0L)

        val found = pool.activeEvents.filterIsInstance<VisualEvent.HitFlash>().firstOrNull { it.at == Vector2Int(3, 3) }
        found shouldBe flash
    }

    test("clear drops every active effect immediately") {
        val pool = VisualEffectPool()
        pool.spawn(VisualEvent.HitFlash(Vector2Int(0, 0), durationMs = 10_000L), nowMs = 0L)
        pool.isPlaying shouldBe true

        pool.clear()
        pool.isPlaying shouldBe false
        pool.activeEvents shouldBe emptyList()
    }
})
