package com.sletmoe.korogue.algorithms.lighting

import com.sletmoe.korogue.algorithms.color.NormalizedRgb
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

private val WHITE = NormalizedRgb(1.0, 1.0, 1.0)

class DiminishingLightValueCalculatorTest : DescribeSpec({

    val calculator = DiminishingLightValueCalculator()

    // MINIMUM_LIGHT_SHIFT_RATIO = 0.10  (private constant in the class)
    // intensity formula when distance <= radius:
    //   minLightShift = radius * 0.10
    //   intensity = 1.0 - (distance - minLightShift) / radius
    //             = 1.0 - distance/radius + 0.10
    //             = 1.10 - distance/radius

    describe("calculateLightValue") {
        it("should return the supplied lightColor unchanged") {
            val color = NormalizedRgb(0.5, 0.2, 0.8)
            val result = calculator.calculateLightValue(color, lightRadius = 10.0, distanceFromLightSource = 0.0)
            result.normalizedColor shouldBe color
        }

        it("should return intensity 0.0 when distance exceeds the light radius") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 5.0, distanceFromLightSource = 6.0)
            result.intensity shouldBeExactly 0.0
        }

        it("should return intensity 0.0 when distance exceeds the radius by any amount") {
            // distance == radius gives 0.10 (not 0) because of the minimum-light-shift;
            // intensity drops to 0 only when distanceFromLightSource > lightRadius.
            val result = calculator.calculateLightValue(WHITE, lightRadius = 5.0, distanceFromLightSource = 5.01)
            result.intensity shouldBeExactly 0.0
        }

        it("should return a higher intensity when closer to the light source") {
            val near = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 1.0)
            val far = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 8.0)
            near.intensity shouldBeGreaterThan far.intensity
        }

        it("should return positive intensity at distance zero") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 0.0)
            result.intensity shouldBeGreaterThan 0.0
        }

        it("should return intensity above 1.0 at distance zero due to the minimum-light-shift floor") {
            // minLightShift = 10.0 * 0.10 = 1.0
            // intensity = 1.0 - (0.0 - 1.0) / 10.0 = 1.0 + 0.1 = 1.10
            val result = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 0.0)
            result.intensity shouldBeGreaterThan 1.0
        }

        it("should respect the minimum-light-shift floor: intensity at minLightShift distance equals 1.0") {
            // minLightShift = radius * 0.10
            val radius = 10.0
            val minLightShift = radius * 0.10 // = 1.0
            // intensity = 1.0 - (minLightShift - minLightShift) / radius = 1.0
            val result =
                calculator.calculateLightValue(
                    WHITE,
                    lightRadius = radius,
                    distanceFromLightSource = minLightShift,
                )
            result.intensity shouldBeExactly 1.0
        }

        it("should return intensity greater than 0.0 at a distance just inside the radius") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 9.9)
            result.intensity shouldBeGreaterThan 0.0
        }

        it("should fall off monotonically: intensity at half-radius is between 0 and intensity at distance zero") {
            val atZero = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 0.0)
            val atHalf = calculator.calculateLightValue(WHITE, lightRadius = 10.0, distanceFromLightSource = 5.0)
            atHalf.intensity shouldBeLessThan atZero.intensity
            atHalf.intensity shouldBeGreaterThan 0.0
        }
    }
})

class GlobalLightValueCalculatorTest : DescribeSpec({

    val calculator = GlobalLightValueCalculator()

    describe("calculateLightValue") {
        it("should always return intensity 1.0 regardless of distance") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 5.0, distanceFromLightSource = 3.0)
            result.intensity shouldBeExactly 1.0
        }

        it("should always return intensity 1.0 even when distance exceeds radius") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 5.0, distanceFromLightSource = 100.0)
            result.intensity shouldBeExactly 1.0
        }

        it("should always return intensity 1.0 at distance zero") {
            val result = calculator.calculateLightValue(WHITE, lightRadius = 5.0, distanceFromLightSource = 0.0)
            result.intensity shouldBeExactly 1.0
        }

        it("should return the supplied lightColor unchanged") {
            val color = NormalizedRgb(0.3, 0.6, 0.9)
            val result = calculator.calculateLightValue(color, lightRadius = 5.0, distanceFromLightSource = 2.0)
            result.normalizedColor shouldBe color
        }
    }
})
