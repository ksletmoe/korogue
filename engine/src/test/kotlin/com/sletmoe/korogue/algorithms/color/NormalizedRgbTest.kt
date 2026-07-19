package com.sletmoe.korogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class NormalizedRgbTest : DescribeSpec({

    val eps = 1e-6f

    describe("NormalizedRgb construction") {
        it("should store r, g, b components exactly") {
            val rgb = NormalizedRgb(0.2, 0.5, 0.8)
            rgb.r shouldBe (0.2 plusOrMinus 1e-9)
            rgb.g shouldBe (0.5 plusOrMinus 1e-9)
            rgb.b shouldBe (0.8 plusOrMinus 1e-9)
        }

        it("should allow zero components") {
            val rgb = NormalizedRgb(0.0, 0.0, 0.0)
            rgb.r shouldBe (0.0 plusOrMinus 1e-9)
            rgb.g shouldBe (0.0 plusOrMinus 1e-9)
            rgb.b shouldBe (0.0 plusOrMinus 1e-9)
        }

        it("should allow full components at 1.0") {
            val rgb = NormalizedRgb(1.0, 1.0, 1.0)
            rgb.r shouldBe (1.0 plusOrMinus 1e-9)
            rgb.g shouldBe (1.0 plusOrMinus 1e-9)
            rgb.b shouldBe (1.0 plusOrMinus 1e-9)
        }
    }

    describe("NormalizedRgb.toColor()") {
        it("should convert mid-range components to GDX Color without clamping") {
            val color = NormalizedRgb(0.25, 0.5, 0.75).toColor()
            color.r shouldBe (0.25f plusOrMinus eps)
            color.g shouldBe (0.5f plusOrMinus eps)
            color.b shouldBe (0.75f plusOrMinus eps)
        }

        it("should always produce alpha of 1.0") {
            val color = NormalizedRgb(0.1, 0.2, 0.3).toColor()
            color.a shouldBe (1.0f plusOrMinus eps)
        }

        it("should clamp components above 1.0 to 1.0") {
            val overBright = NormalizedRgb(1.5, 2.0, 0.5)
            val color = overBright.toColor()
            color.r shouldBe (1.0f plusOrMinus eps)
            color.g shouldBe (1.0f plusOrMinus eps)
            color.b shouldBe (0.5f plusOrMinus eps)
        }

        it("should not clamp components at exactly 1.0") {
            val color = NormalizedRgb(1.0, 1.0, 1.0).toColor()
            color.r shouldBe (1.0f plusOrMinus eps)
            color.g shouldBe (1.0f plusOrMinus eps)
            color.b shouldBe (1.0f plusOrMinus eps)
        }

        it("should clamp negative components up to 0.0") {
            val color = NormalizedRgb(-0.5, -2.0, 0.5).toColor()
            color.r shouldBe (0.0f plusOrMinus eps)
            color.g shouldBe (0.0f plusOrMinus eps)
            color.b shouldBe (0.5f plusOrMinus eps)
        }
    }

    describe("Color.toNormalizedRgb()") {
        it("should round-trip a mid-range GDX Color through NormalizedRgb") {
            val original = Color(0.3f, 0.6f, 0.9f, 1.0f)
            val rgb = original.toNormalizedRgb()
            rgb.r shouldBe (0.3 plusOrMinus 1e-6)
            rgb.g shouldBe (0.6 plusOrMinus 1e-6)
            rgb.b shouldBe (0.9 plusOrMinus 1e-6)
        }

        it("should round-trip black (0,0,0)") {
            val black = Color(0f, 0f, 0f, 1f)
            val rgb = black.toNormalizedRgb()
            rgb.r shouldBe (0.0 plusOrMinus 1e-9)
            rgb.g shouldBe (0.0 plusOrMinus 1e-9)
            rgb.b shouldBe (0.0 plusOrMinus 1e-9)
        }

        it("should round-trip white (1,1,1)") {
            val white = Color(1f, 1f, 1f, 1f)
            val rgb = white.toNormalizedRgb()
            rgb.r shouldBe (1.0 plusOrMinus 1e-9)
            rgb.g shouldBe (1.0 plusOrMinus 1e-9)
            rgb.b shouldBe (1.0 plusOrMinus 1e-9)
        }
    }

    describe("NormalizedRgb times NormalizedRgb (multiply blend mode)") {
        it("should multiply components pairwise") {
            val a = NormalizedRgb(0.5, 0.4, 0.8)
            val b = NormalizedRgb(0.5, 0.25, 0.5)
            val result = a * b
            // 0.5*0.5=0.25, 0.4*0.25=0.1, 0.8*0.5=0.4
            result.r shouldBe (0.25 plusOrMinus 1e-9)
            result.g shouldBe (0.10 plusOrMinus 1e-9)
            result.b shouldBe (0.40 plusOrMinus 1e-9)
        }

        it("should produce zero when either operand is zero") {
            val a = NormalizedRgb(1.0, 1.0, 1.0)
            val b = NormalizedRgb(0.0, 0.0, 0.0)
            val result = a * b
            result.r shouldBe (0.0 plusOrMinus 1e-9)
            result.g shouldBe (0.0 plusOrMinus 1e-9)
            result.b shouldBe (0.0 plusOrMinus 1e-9)
        }

        it("should be identity when multiplied by (1,1,1)") {
            val a = NormalizedRgb(0.3, 0.6, 0.9)
            val identity = NormalizedRgb(1.0, 1.0, 1.0)
            val result = a * identity
            result.r shouldBe (0.3 plusOrMinus 1e-9)
            result.g shouldBe (0.6 plusOrMinus 1e-9)
            result.b shouldBe (0.9 plusOrMinus 1e-9)
        }
    }

    describe("NormalizedRgb times Double (scalar multiply)") {
        it("should scale all components by the multiplier") {
            val rgb = NormalizedRgb(0.4, 0.2, 0.6)
            val result = rgb * 0.5
            result.r shouldBe (0.2 plusOrMinus 1e-9)
            result.g shouldBe (0.1 plusOrMinus 1e-9)
            result.b shouldBe (0.3 plusOrMinus 1e-9)
        }

        it("should produce zero when scaled by 0.0") {
            val rgb = NormalizedRgb(0.5, 0.5, 0.5)
            val result = rgb * 0.0
            result.r shouldBe (0.0 plusOrMinus 1e-9)
            result.g shouldBe (0.0 plusOrMinus 1e-9)
            result.b shouldBe (0.0 plusOrMinus 1e-9)
        }

        it("should produce values above 1.0 when scaled above 1.0 (toColor clamps later)") {
            val rgb = NormalizedRgb(0.8, 0.6, 0.4)
            val result = rgb * 2.0
            result.r shouldBe (1.6 plusOrMinus 1e-9)
            result.g shouldBe (1.2 plusOrMinus 1e-9)
            result.b shouldBe (0.8 plusOrMinus 1e-9)
        }
    }

    describe("companion constants") {
        it("BLACK and WHITE are the neutral identities") {
            NormalizedRgb.BLACK shouldBe NormalizedRgb(0.0, 0.0, 0.0)
            NormalizedRgb.WHITE shouldBe NormalizedRgb(1.0, 1.0, 1.0)
        }

        it("WHITE is the multiply identity and BLACK the absorber") {
            val c = NormalizedRgb(0.3, 0.6, 0.9)
            (c * NormalizedRgb.WHITE) shouldBe c
            (c * NormalizedRgb.BLACK) shouldBe NormalizedRgb.BLACK
        }

        it("palette constants mirror the GDX Color they re-export") {
            NormalizedRgb.YELLOW shouldBe Color.YELLOW.toNormalizedRgb()
            NormalizedRgb.GOLD shouldBe Color.GOLD.toNormalizedRgb()
            NormalizedRgb.CYAN shouldBe Color.CYAN.toNormalizedRgb()
        }
    }

    describe("NormalizedRgb.fromColor()") {
        it("drops alpha and keeps rgb channels") {
            val rgb = NormalizedRgb.fromColor(Color(0.3f, 0.6f, 0.9f, 0.5f))
            rgb.r shouldBe (0.3 plusOrMinus 1e-6)
            rgb.g shouldBe (0.6 plusOrMinus 1e-6)
            rgb.b shouldBe (0.9 plusOrMinus 1e-6)
        }

        it("is the same conversion as the Color.toNormalizedRgb() extension") {
            val color = Color(0.2f, 0.4f, 0.8f, 1f)
            NormalizedRgb.fromColor(color) shouldBe color.toNormalizedRgb()
        }
    }

    describe("NormalizedRgb.fromHex()") {
        it("parses a 6-digit hex string") {
            val rgb = NormalizedRgb.fromHex("ff0000")
            rgb.r shouldBe (1.0 plusOrMinus 1e-6)
            rgb.g shouldBe (0.0 plusOrMinus 1e-6)
            rgb.b shouldBe (0.0 plusOrMinus 1e-6)
        }

        it("drops the alpha of an 8-digit hex string") {
            val rgb = NormalizedRgb.fromHex("0000ff80")
            rgb.r shouldBe (0.0 plusOrMinus 1e-6)
            rgb.g shouldBe (0.0 plusOrMinus 1e-6)
            rgb.b shouldBe (1.0 plusOrMinus 1e-6)
        }

        it("accepts an optional leading # (as the KDoc documents)") {
            val rgb = NormalizedRgb.fromHex("#00ff00")
            rgb.r shouldBe (0.0 plusOrMinus 1e-6)
            rgb.g shouldBe (1.0 plusOrMinus 1e-6)
            rgb.b shouldBe (0.0 plusOrMinus 1e-6)
        }
    }

    describe("NormalizedRgb.lerp()") {
        val a = NormalizedRgb(0.0, 0.2, 1.0)
        val b = NormalizedRgb(1.0, 0.6, 0.0)

        it("returns this colour at t = 0.0") {
            a.lerp(b, 0.0) shouldBe a
        }

        it("returns the other colour at t = 1.0") {
            a.lerp(b, 1.0).let {
                it.r shouldBe (b.r plusOrMinus 1e-9)
                it.g shouldBe (b.g plusOrMinus 1e-9)
                it.b shouldBe (b.b plusOrMinus 1e-9)
            }
        }

        it("returns the per-channel midpoint at t = 0.5") {
            val mid = a.lerp(b, 0.5)
            mid.r shouldBe (0.5 plusOrMinus 1e-9)
            mid.g shouldBe (0.4 plusOrMinus 1e-9)
            mid.b shouldBe (0.5 plusOrMinus 1e-9)
        }

        it("extrapolates past 1.0 without clamping (model space is unclamped)") {
            val past = a.lerp(b, 2.0)
            past.r shouldBe (2.0 plusOrMinus 1e-9)
            past.g shouldBe (1.0 plusOrMinus 1e-9)
            past.b shouldBe (-1.0 plusOrMinus 1e-9)
        }
    }

    describe("Color.tintedByLight()") {
        it("matches the (toNormalizedRgb() * (lightColor * intensity)).toColor() chain it replaces") {
            val base = Color(0.6f, 0.5f, 0.4f, 1f)
            val lightColor = NormalizedRgb(0.9, 0.8, 0.7)
            val intensity = 0.65

            val fused = base.tintedByLight(lightColor, intensity)
            val chained = (base.toNormalizedRgb() * (lightColor * intensity)).toColor()

            fused.r shouldBe (chained.r plusOrMinus eps)
            fused.g shouldBe (chained.g plusOrMinus eps)
            fused.b shouldBe (chained.b plusOrMinus eps)
            fused.a shouldBe (1.0f plusOrMinus eps)
        }

        it("is identity at intensity 1.0 with a white lightColor") {
            val base = Color(0.3f, 0.6f, 0.9f, 1f)
            val tinted = base.tintedByLight(NormalizedRgb(1.0, 1.0, 1.0), 1.0)
            tinted.r shouldBe (0.3f plusOrMinus eps)
            tinted.g shouldBe (0.6f plusOrMinus eps)
            tinted.b shouldBe (0.9f plusOrMinus eps)
        }

        it("is black at intensity 0.0") {
            val base = Color(0.8f, 0.8f, 0.8f, 1f)
            val tinted = base.tintedByLight(NormalizedRgb(1.0, 1.0, 1.0), 0.0)
            tinted.r shouldBe (0.0f plusOrMinus eps)
            tinted.g shouldBe (0.0f plusOrMinus eps)
            tinted.b shouldBe (0.0f plusOrMinus eps)
        }

        it("clamps a result above 1.0 down to 1.0") {
            val base = Color(1f, 1f, 1f, 1f)
            val tinted = base.tintedByLight(NormalizedRgb(1.0, 1.0, 1.0), 2.0)
            tinted.r shouldBe (1.0f plusOrMinus eps)
            tinted.g shouldBe (1.0f plusOrMinus eps)
            tinted.b shouldBe (1.0f plusOrMinus eps)
        }
    }
})
