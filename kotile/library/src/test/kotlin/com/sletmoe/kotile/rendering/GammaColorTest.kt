package com.sletmoe.kotile.rendering

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the sRGB ↔ linear conversion behind the gamma-correct
 * downsample (krogue-1zo). These run everywhere and lock the CPU-side curve that
 * the [GammaDownsample] GLSL mirrors; the shader itself is exercised on real
 * pixels by the GL-gated integration test.
 */
class GammaColorTest : FunSpec({

    test("endpoints are fixed points (0 and 1 map to themselves)") {
        GammaColor.toLinear(0f) shouldBe (0f plusOrMinus 1e-6f)
        GammaColor.toLinear(1f) shouldBe (1f plusOrMinus 1e-6f)
        GammaColor.toSrgb(0f) shouldBe (0f plusOrMinus 1e-6f)
        GammaColor.toSrgb(1f) shouldBe (1f plusOrMinus 1e-6f)
    }

    test("toLinear and toSrgb are inverses across the range") {
        var v = 0f
        while (v <= 1f) {
            GammaColor.toSrgb(GammaColor.toLinear(v)) shouldBe (v plusOrMinus 1e-4f)
            v += 0.01f
        }
    }

    test("the linear midpoint of black and white re-encodes to ~0.735 sRGB, not 0.5") {
        // The whole point of a gamma-correct average: decode both endpoints, average in linear light
        // (0.5), re-encode. That lands near 188/255, far above the naive encoded-space midpoint of
        // 128/255 -- so an edge stays the correct brightness instead of going muddy.
        val linearAverage = (GammaColor.toLinear(0f) + GammaColor.toLinear(1f)) / 2f
        linearAverage shouldBe (0.5f plusOrMinus 1e-6f)
        GammaColor.toSrgb(linearAverage) shouldBe (0.7353f plusOrMinus 1e-3f)
        // Sanity: the naive (wrong) answer this replaces would be 0.5.
    }

    test("the low-slope linear segment holds near black") {
        // Below the 0.04045 knee the curve is a straight 1/12.92 line.
        GammaColor.toLinear(0.04f) shouldBe (0.04f / 12.92f plusOrMinus 1e-6f)
    }
})
