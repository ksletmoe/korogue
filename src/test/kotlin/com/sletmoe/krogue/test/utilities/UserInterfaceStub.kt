package com.sletmoe.krogue.test.utilities

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.ui.UserInterface
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

// Implements a UserInterface on top of AsciiPanel, but without trying to display / run a JFrame
class UserInterfaceStub(override val widthInCharacters: Int, override val heightInCharacters: Int) : UserInterface {
    val asciiPanel = AsciiPanel(widthInCharacters, heightInCharacters)

    override fun refresh() {}
    override fun repaintCharacters(bounds: Rectangle) {
        TODO("Not yet implemented")
    }

    override fun getInput(): InputEvent? { return null }

    override fun drawCharacter(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        drawCharacter(AsciiCharacterData(character, foregroundColor, backgroundColor), x, y)
    }

    override fun drawCharacter(characterData: AsciiCharacterData, x: Int, y: Int) {
        asciiPanel.write(characterData, x, y)
    }

    override fun keyTyped(e: KeyEvent?) {}
    override fun keyPressed(e: KeyEvent?) {}
    override fun keyReleased(e: KeyEvent?) {}
    override fun mouseClicked(e: MouseEvent?) {}
    override fun mousePressed(e: MouseEvent?) {}
    override fun mouseReleased(e: MouseEvent?) {}
    override fun mouseEntered(e: MouseEvent?) {}
    override fun mouseExited(e: MouseEvent?) {}
}
