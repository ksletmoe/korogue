package com.sletmoe.krogue.ui

import asciiPanel.AsciiCharacterData
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyListener
import java.awt.event.MouseListener

interface UserInterface : KeyListener, MouseListener {
    val widthInCharacters: Int
    val heightInCharacters: Int

    fun refresh()

    fun repaintCharacters(bounds: Rectangle)

    fun getInput(): InputEvent?

    fun drawCharacter(
        character: Char,
        foregroundColor: Color,
        backgroundColor: Color,
        x: Int,
        y: Int,
    )

    fun drawCharacter(
        characterData: AsciiCharacterData,
        x: Int,
        y: Int,
    )
}
