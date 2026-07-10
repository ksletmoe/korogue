package com.sletmoe.kotile.rendering

import com.badlogic.gdx.graphics.g2d.TextureRegion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pure motion/lifetime logic for [EffectsLayer], exercised without a GL context.
 * Rendering (which needs GL) is covered by the headless-GL integration tests;
 * here we only drive [EffectsLayer.update] and inspect state.
 *
 * Effects reference an empty [TextureRegion] (no backing texture) since these
 * tests never draw.
 */
class EffectsLayerTest : FunSpec({
    fun effect(
        pxX: Float = 0f,
        pxY: Float = 0f,
        velXPerMs: Float = 0f,
        velYPerMs: Float = 0f,
        lifetimeMs: Long? = null,
    ) = Effect(
        pxX = pxX,
        pxY = pxY,
        w = 1f,
        h = 1f,
        region = TextureRegion(),
        velXPerMs = velXPerMs,
        velYPerMs = velYPerMs,
        lifetimeMs = lifetimeMs,
    )

    test("update advances position by velocity times elapsed time") {
        val layer = EffectsLayer()
        val e = layer.spawn(effect(pxX = 10f, pxY = 20f, velXPerMs = 0.5f, velYPerMs = -0.25f))

        layer.update(100)

        e.pxX shouldBe (60f plusOrMinus 1e-3f) // 10 + 0.5*100
        e.pxY shouldBe (-5f plusOrMinus 1e-3f) // 20 - 0.25*100
        e.ageMs shouldBe 100L
    }

    test("motion accumulates across successive updates") {
        val layer = EffectsLayer()
        val e = layer.spawn(effect(velXPerMs = 1f))

        layer.update(30)
        layer.update(70)

        e.pxX shouldBe (100f plusOrMinus 1e-3f)
        e.ageMs shouldBe 100L
    }

    test("an effect is dropped once its lifetime elapses") {
        val layer = EffectsLayer()
        layer.spawn(effect(lifetimeMs = 150))
        layer.activeCount shouldBe 1

        layer.update(100)
        layer.activeCount shouldBe 1 // 100 < 150: still active

        layer.update(50)
        layer.activeCount shouldBe 0 // 150 >= 150: expired and removed
    }

    test("a null-lifetime effect never expires on its own") {
        val layer = EffectsLayer()
        val e = layer.spawn(effect(lifetimeMs = null))

        layer.update(1_000_000)

        layer.activeCount shouldBe 1
        e.expired shouldBe false
    }

    test("expiring one effect does not disturb others in the same update") {
        val layer = EffectsLayer()
        val shortLived = layer.spawn(effect(lifetimeMs = 50))
        val survivor = layer.spawn(effect(velXPerMs = 2f, lifetimeMs = 500))

        layer.update(100) // shortLived expires; survivor advances

        layer.activeCount shouldBe 1
        layer.remove(shortLived) shouldBe false // already gone
        survivor.pxX shouldBe (200f plusOrMinus 1e-3f)
    }

    test("remove and clear drop effects explicitly") {
        val layer = EffectsLayer()
        val a = layer.spawn(effect())
        layer.spawn(effect())

        layer.remove(a) shouldBe true
        layer.activeCount shouldBe 1

        layer.clear()
        layer.activeCount shouldBe 0
    }
})
