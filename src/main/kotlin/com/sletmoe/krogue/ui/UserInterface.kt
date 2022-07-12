package com.sletmoe.krogue.ui

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.graphics.AsciiSubpanelComponent
import com.sletmoe.krogue.graphics.repaintCharacters
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.LinkedList
import java.util.Queue
import javax.swing.JFrame

interface UserInterface : KeyListener, MouseListener {
    val widthInCharacters: Int
    val heightInCharacters: Int

    fun refresh()
    fun repaintCharacters(bounds: Rectangle)
    fun getInput(): InputEvent?
    fun drawCharacter(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int)
    fun drawCharacter(characterData: AsciiCharacterData, x: Int, y: Int)
}
