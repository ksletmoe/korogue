package com.sletmoe.krogue.algorithms.color

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class ColorTransformersTest : DescribeSpec({

    val eps = 1e-5f

    describe("multiplicationTransformer(Color)") {
        it("should multiply input color by the multiplier color channel-wise") {
            // multiply(input=(0.8,0.6,0.4), multiplier=(0.5,0.5,0.5)):
            // r=0.8*0.5=0.4, g=0.6*0.5=0.3, b=0.4*0.5=0.2
            val multiplier = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val transformer = multiplicationTransformer(multiplier)
            val input = Color(0.8f, 0.6f, 0.4f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.4f plusOrMinus eps)
            result.g shouldBe (0.3f plusOrMinus eps)
            result.b shouldBe (0.2f plusOrMinus eps)
        }

        it("should return black when multiplier is black") {
            val black = Color(0.0f, 0.0f, 0.0f, 1.0f)
            val transformer = multiplicationTransformer(black)
            val input = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.0f plusOrMinus eps)
            result.g shouldBe (0.0f plusOrMinus eps)
            result.b shouldBe (0.0f plusOrMinus eps)
        }

        it("should be identity when multiplier is white") {
            val white = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val transformer = multiplicationTransformer(white)
            val input = Color(0.3f, 0.6f, 0.9f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.3f plusOrMinus eps)
            result.g shouldBe (0.6f plusOrMinus eps)
            result.b shouldBe (0.9f plusOrMinus eps)
        }

        it("should produce alpha of 1.0 in the result") {
            val multiplier = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val transformer = multiplicationTransformer(multiplier)
            val input = Color(0.4f, 0.4f, 0.4f, 1.0f)
            val result = transformer(input)
            result.a shouldBe (1.0f plusOrMinus eps)
        }
    }

    describe("multiplicationTransformer(Double)") {
        it("should scale all channels by the scalar multiplier") {
            // input=(0.8,0.6,0.4) * 0.5 → (0.4,0.3,0.2)
            val transformer = multiplicationTransformer(0.5)
            val input = Color(0.8f, 0.6f, 0.4f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.4f plusOrMinus eps)
            result.g shouldBe (0.3f plusOrMinus eps)
            result.b shouldBe (0.2f plusOrMinus eps)
        }

        it("should return black when scalar multiplier is 0.0") {
            val transformer = multiplicationTransformer(0.0)
            val input = Color(1.0f, 1.0f, 1.0f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.0f plusOrMinus eps)
            result.g shouldBe (0.0f plusOrMinus eps)
            result.b shouldBe (0.0f plusOrMinus eps)
        }

        it("should be identity when scalar multiplier is 1.0") {
            val transformer = multiplicationTransformer(1.0)
            val input = Color(0.3f, 0.6f, 0.9f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (0.3f plusOrMinus eps)
            result.g shouldBe (0.6f plusOrMinus eps)
            result.b shouldBe (0.9f plusOrMinus eps)
        }

        it("should clamp result to 1.0 when scalar multiplier exceeds 1.0") {
            // input=(0.8,0.8,0.8) * 2.0 → NormalizedRgb=(1.6,1.6,1.6) → clamped to 1.0
            val transformer = multiplicationTransformer(2.0)
            val input = Color(0.8f, 0.8f, 0.8f, 1.0f)
            val result = transformer(input)
            result.r shouldBe (1.0f plusOrMinus eps)
            result.g shouldBe (1.0f plusOrMinus eps)
            result.b shouldBe (1.0f plusOrMinus eps)
        }

        it("should produce alpha of 1.0 in the result") {
            val transformer = multiplicationTransformer(0.5)
            val input = Color(0.5f, 0.5f, 0.5f, 1.0f)
            val result = transformer(input)
            result.a shouldBe (1.0f plusOrMinus eps)
        }
    }
})
