package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.scaled
import java.awt.Rectangle

fun AsciiPanel.repaintCharacters(bounds: Rectangle) {
    repaint(bounds.scaled(charWidth, charHeight))
}

val AsciiPanel.characterGrid: Grid<AsciiCharacterData>
    get() {
        val grid = Grid(
            widthInCharacters,
            heightInCharacters,
            AsciiCharacterData(' ', defaultForegroundColor, defaultBackgroundColor)
        )
        (0..grid.lastColumnIndex).forEach { x ->
            (0..grid.lastRowIndex).forEach { y ->
                grid[x, y] = characters[x][y]
            }
        }

        return grid
    }
