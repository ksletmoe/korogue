package com.sletmoe.krogue.test.utilities

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.utilities.Grid
import java.awt.Color

fun asciiGrid(
    rows: List<String>,
    foregroundColor: Color,
    backgroundColor: Color,
): Grid<AsciiCharacterData> {
    val defaultCharacter = AsciiCharacterData(rows.first().first(), foregroundColor, backgroundColor)
    val grid = Grid(rows.first().length, rows.size, defaultCharacter)

    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, char ->
            grid[x, y] = AsciiCharacterData(char, foregroundColor, backgroundColor)
        }
    }

    return grid
}
