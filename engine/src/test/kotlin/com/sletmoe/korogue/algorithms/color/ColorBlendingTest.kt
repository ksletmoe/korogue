package com.sletmoe.korogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Derived expected values:
 *
 * multiply: result[c] = a[c] * b[c]
 *
 * screen:   result[c] = 1.0 - (1.0 - a[c]) * (1.0 - b[c])
 *
 * softLight (pegtop): result[c] = (1 - a[c]) * a[c] * b[c] + a[c] * screen(a[c], b[c])
 *   e.g. a=0.5, b=0.5: (0.5)(0.5)(0.5) + 0.5*(1-(0.5)(0.5)) = 0.125 + 0.5*0.75 = 0.5
 *   e.g. a=0.0, b=0.5: (1)(0)(0.5) + 0*screen(0,0.5) = 0.0
 *   e.g. a=1.0, b=0.5: (0)(1)(0.5) + 1*screen(1,0.5) = 0 + 1*(1-0*0.5) = 1.0
 *
 * lightenOnly: result[c] = max(a[c], b[c])
 */
class ColorBlendingTest : DescribeSpec({

    val eps = 1e-5f
    val dEps = 1e-9

    describe("ColorBlending.multiply(Color, Color)") {
        it("should multiply channel values pairwise") {
            // a=(1.0, 0.5, 0.25), b=(0.8, 0.4, 0.5)
            // r=1.0*0.8=0.8, g=0.5*0.4=0.2, b=0.25*0.5=0.125
            val a = Color(1.0f, 0.5f, 0.25f, 1.0f)
            val b = Color(0.8f, 0.4f, 0.5f, 1.0f)
            val result = ColorBlending.multiply(a, b)
            result.r shouldBe (0.8f plusOrMinus eps)
            result.g shouldBe (0.2f plusOrMinus eps)
            result.b shouldBe (0.125f plusOrMinus eps)
        }

        it("should produce black when multiplied by black") {
            val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val black = Color(0.0f, 0.0f, 0.0f, 1.0f)
            val result = ColorBlending.multiply(white, black)
            result.r shouldBe (0.0f plusOrMinus eps)
            result.g shouldBe (0.0f plusOrMinus eps)
            result.b shouldBe (0.0f plusOrMinus eps)
        }

        it("should be identity when multiplied by white") {
            val color = Color(0.3f, 0.6f, 0.9f, 1.0f)
            val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val result = ColorBlending.multiply(color, white)
            result.r shouldBe (0.3f plusOrMinus eps)
            result.g shouldBe (0.6f plusOrMinus eps)
            result.b shouldBe (0.9f plusOrMinus eps)
        }
    }

    describe("ColorBlending.screen(Color, Color)") {
        it("should apply screen formula per channel") {
            // a=(0.5,0.5,0.5), b=(0.5,0.5,0.5)
            // each: 1-(1-0.5)*(1-0.5)=1-0.25=0.75
            val a = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val b = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val result = ColorBlending.screen(a, b)
            result.r shouldBe (0.75f plusOrMinus eps)
            result.g shouldBe (0.75f plusOrMinus eps)
            result.b shouldBe (0.75f plusOrMinus eps)
        }

        it("should return white when either input is white") {
            // screen(1.0, x) = 1-(1-1)*(1-x) = 1-0 = 1.0
            val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val other = Color(0.3f, 0.4f, 0.5f, 1.0f)
            val result = ColorBlending.screen(white, other)
            result.r shouldBe (1.0f plusOrMinus eps)
            result.g shouldBe (1.0f plusOrMinus eps)
            result.b shouldBe (1.0f plusOrMinus eps)
        }

        it("should be identity when screened with black") {
            // screen(x, 0) = 1-(1-x)*(1-0) = 1-(1-x) = x
            val color = Color(0.3f, 0.6f, 0.9f, 1.0f)
            val black = Color(0.0f, 0.0f, 0.0f, 1.0f)
            val result = ColorBlending.screen(color, black)
            result.r shouldBe (0.3f plusOrMinus eps)
            result.g shouldBe (0.6f plusOrMinus eps)
            result.b shouldBe (0.9f plusOrMinus eps)
        }

        it("should produce same result for screen(r,g,b) and screen with separate channels mixing") {
            // screen(1,0,0) with screen(0,1,0): r=1-(0)(1)=1, g=1-(1)(0)=1, b=1-(1)(1)=0
            val red = Color(1.0f, 0.0f, 0.0f, 1.0f)
            val green = Color(0.0f, 1.0f, 0.0f, 1.0f)
            val result = ColorBlending.screen(red, green)
            result.r shouldBe (1.0f plusOrMinus eps)
            result.g shouldBe (1.0f plusOrMinus eps)
            result.b shouldBe (0.0f plusOrMinus eps)
        }
    }

    describe("ColorBlending.screen(NormalizedRgb, NormalizedRgb)") {
        it("should produce same result as Color overload for equivalent inputs") {
            val a = NormalizedRgb(0.5, 0.5, 0.5)
            val b = NormalizedRgb(0.5, 0.5, 0.5)
            val result = ColorBlending.screen(a, b)
            result.r shouldBe (0.75 plusOrMinus dEps)
            result.g shouldBe (0.75 plusOrMinus dEps)
            result.b shouldBe (0.75 plusOrMinus dEps)
        }
    }

    describe("ColorBlending.screen(Double, Double)") {
        it("should compute 1.0 - (1.0-a)*(1.0-b)") {
            // screen(0.5, 0.5) = 1 - 0.5*0.5 = 0.75
            ColorBlending.screen(0.5, 0.5) shouldBe (0.75 plusOrMinus dEps)
        }

        it("should return 1.0 when either value is 1.0") {
            ColorBlending.screen(1.0, 0.3) shouldBe (1.0 plusOrMinus dEps)
            ColorBlending.screen(0.7, 1.0) shouldBe (1.0 plusOrMinus dEps)
        }

        it("should be identity when one operand is 0.0") {
            ColorBlending.screen(0.6, 0.0) shouldBe (0.6 plusOrMinus dEps)
            ColorBlending.screen(0.0, 0.4) shouldBe (0.4 plusOrMinus dEps)
        }

        it("should return 0.0 when both operands are 0.0") {
            ColorBlending.screen(0.0, 0.0) shouldBe (0.0 plusOrMinus dEps)
        }
    }

    describe("ColorBlending.softLight(Color, Color)") {
        it("should return same value when both inputs are 0.5 (pegtop fixed point)") {
            // softLight(0.5, 0.5) = (0.5)(0.5)(0.5) + 0.5*screen(0.5,0.5)
            //                     = 0.125 + 0.5*0.75 = 0.5
            val mid = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val result = ColorBlending.softLight(mid, mid)
            result.r shouldBe (0.5f plusOrMinus eps)
            result.g shouldBe (0.5f plusOrMinus eps)
            result.b shouldBe (0.5f plusOrMinus eps)
        }

        it("should return 0.0 when base layer (a) is 0.0") {
            // softLight(0.0, b) = (1-0)*0*b + 0*screen(0,b) = 0
            val black = Color(0.0f, 0.0f, 0.0f, 1.0f)
            val blend = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val result = ColorBlending.softLight(black, blend)
            result.r shouldBe (0.0f plusOrMinus eps)
            result.g shouldBe (0.0f plusOrMinus eps)
            result.b shouldBe (0.0f plusOrMinus eps)
        }

        it("should return 1.0 when base layer (a) is 1.0") {
            // softLight(1.0, b) = (1-1)*1*b + 1*screen(1,b) = 0 + screen(1,b)
            // screen(1,b) = 1-(1-1)*(1-b) = 1
            val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val blend = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val result = ColorBlending.softLight(white, blend)
            result.r shouldBe (1.0f plusOrMinus eps)
            result.g shouldBe (1.0f plusOrMinus eps)
            result.b shouldBe (1.0f plusOrMinus eps)
        }
    }

    describe("ColorBlending.softLight(NormalizedRgb, NormalizedRgb)") {
        it("should apply pegtop formula per channel") {
            // softLight(a=0.25, b=0.75):
            // screen(0.25, 0.75) = 1-(0.75)*(0.25) = 1-0.1875 = 0.8125
            // (1-0.25)*0.25*0.75 + 0.25*0.8125 = 0.140625 + 0.203125 = 0.34375
            val a = NormalizedRgb(0.25, 0.25, 0.25)
            val b = NormalizedRgb(0.75, 0.75, 0.75)
            val result = ColorBlending.softLight(a, b)
            result.r shouldBe (0.34375 plusOrMinus dEps)
            result.g shouldBe (0.34375 plusOrMinus dEps)
            result.b shouldBe (0.34375 plusOrMinus dEps)
        }
    }

    describe("ColorBlending.lightenOnly(NormalizedRgb, NormalizedRgb)") {
        it("should select the maximum component from each pair") {
            val a = NormalizedRgb(0.3, 0.8, 0.1)
            val b = NormalizedRgb(0.7, 0.2, 0.1)
            val result = ColorBlending.lightenOnly(a, b)
            result.r shouldBe (0.7 plusOrMinus dEps)
            result.g shouldBe (0.8 plusOrMinus dEps)
            result.b shouldBe (0.1 plusOrMinus dEps)
        }

        it("should return the brighter color when one is entirely brighter") {
            val dark = NormalizedRgb(0.1, 0.1, 0.1)
            val bright = NormalizedRgb(0.9, 0.9, 0.9)
            val result = ColorBlending.lightenOnly(dark, bright)
            result.r shouldBe (0.9 plusOrMinus dEps)
            result.g shouldBe (0.9 plusOrMinus dEps)
            result.b shouldBe (0.9 plusOrMinus dEps)
        }

        it("should be commutative") {
            val a = NormalizedRgb(0.4, 0.6, 0.2)
            val b = NormalizedRgb(0.5, 0.3, 0.8)
            val ab = ColorBlending.lightenOnly(a, b)
            val ba = ColorBlending.lightenOnly(b, a)
            ab.r shouldBe (ba.r plusOrMinus dEps)
            ab.g shouldBe (ba.g plusOrMinus dEps)
            ab.b shouldBe (ba.b plusOrMinus dEps)
        }
    }
})
