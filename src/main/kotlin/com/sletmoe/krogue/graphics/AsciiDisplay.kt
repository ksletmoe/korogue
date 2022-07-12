package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.ui.UserInterface
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.initialize
import com.sletmoe.krogue.utilities.plus
import java.awt.Color
import java.awt.Point

class AsciiDisplay(ui: UserInterface) : AsciiSubpanel(ui) {
    private var contents: Grid<AsciiCharacterData> = Grid(bounds.width, bounds.height, defaultFillCharacter)

    override fun onNewBounds() {
        val oldContents = contents
        contents = Grid(bounds.width, bounds.height, defaultFillCharacter)
        clear()

        oldContents.forEachIndexed { point, asciiCharacterData ->
            if (relativeContentBounds.contains(point)) {
                write(
                    asciiCharacterData.character,
                    asciiCharacterData.foregroundColor,
                    asciiCharacterData.backgroundColor,
                    point.x,
                    point.y,
                )
            }
        }
    }

    override fun write(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        contents[x, y] = AsciiCharacterData(character, foregroundColor, backgroundColor)
        super.write(character, foregroundColor, backgroundColor, x, y)
    }

    fun write(str: String, foregroundColor: Color, backgroundColor: Color, startingAt: Point) {
        if (
            !relativeContentBounds.contains(startingAt)
            || !relativeContentBounds.contains(startingAt + Point(str.length, 0))
        ) {
            throw RuntimeException("(${startingAt.x}, ${startingAt.y}) is not within $relativeContentBounds")
        }

        str.forEachIndexed { idx, character ->
            write(character, foregroundColor, backgroundColor, startingAt.x + idx, startingAt.y)
        }
    }

    companion object {
        fun create(
            ui: UserInterface, init: AsciiDisplay.() -> Unit
        ): AsciiDisplay = initialize(AsciiDisplay(ui), init)
    }
}




