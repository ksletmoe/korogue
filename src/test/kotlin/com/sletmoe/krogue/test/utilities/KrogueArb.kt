package com.sletmoe.krogue.test.utilities

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.lastX
import com.sletmoe.krogue.utilities.lastY
import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.exhaustive.exhaustive
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle

object KrogueArb {
    fun coordinates(bounds: Rectangle): Exhaustive<Point> {
        val coordinates: MutableList<Point> = mutableListOf()
        (bounds.x..bounds.lastX).forEach { x ->
            (bounds.y..bounds.lastY).forEach { y ->
                coordinates.add(Point(x, y))
            }
        }

        return coordinates.exhaustive()
    }

    val color =
        arbitrary {
            Color(
                Arb.positiveInt(max = 255).bind(),
                Arb.positiveInt(max = 255).bind(),
                Arb.positiveInt(max = 255).bind(),
            )
        }

    val asciiCharacterData =
        arbitrary {
            AsciiCharacterData(Arb.positiveInt(max = 255).bind().toChar(), color.bind(), color.bind())
        }

    val asciiCharacterDataGrid =
        arbitrary {
            val grid =
                Grid(Arb.positiveInt(max = 100).bind(), Arb.positiveInt(max = 100).bind(), asciiCharacterData.bind())
            (0..grid.lastColumnIndex).forEach { x ->
                (0..grid.lastRowIndex).forEach { y ->
                    grid[x, y] = asciiCharacterData.bind()
                }
            }

            grid
        }
}
