
package com.sletmoe.kotile.utilities

import javafx.scene.paint.Color

object ColorExtensions {
    operator fun Color.times(other: Color): Color {
        val r = red * other.red
        val g = green * other.green
        val b = blue * other.blue
        val a = opacity * other.opacity

        return Color(r, g, b, a)
    }

    operator fun Color.plus(other: Color): Color = Color(
        red + other.red,
        green + other.green,
        blue + other.blue,
        opacity + other.opacity,
    )

    fun blendAlphaChannel(fromC: Double, fromA: Double, toC: Double, toA: Double, outA: Double): Double {
        return ((fromC * fromA) + (toC * toA * (1.0 - fromA))) / outA
    }

    fun Color.alphaBlend(to: Color): Color {
        val outA = opacity + to.opacity * (1.0 - opacity)
        val outR = blendAlphaChannel(red, opacity, to.red, to.opacity, outA)
        val outG = blendAlphaChannel(green, opacity, to.green, to.opacity, outA)
        val outB = blendAlphaChannel(blue, opacity, to.blue, to.opacity, outA)

        return Color(outR, outG, outB, outA)
    }
}
