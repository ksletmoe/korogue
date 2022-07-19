package com.sletmoe.krogue.ui

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import com.sletmoe.krogue.graphics.AsciiSubpanelGroup
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.LinkedList
import java.util.Queue
import javax.swing.JFrame

class AsciiPanelUi(
    name: String?, sizeInCharacters: Dimension, font: AsciiFont, backgroundColor: Color = Color.black
) : JFrame(name), UserInterface, KeyListener, MouseListener {
    private val inputQueue: Queue<InputEvent> = LinkedList()
    private val resizableAsciiPanel = ResizableAsciiPanel(sizeInCharacters, font, backgroundColor)

    override val widthInCharacters: Int
        get() = resizableAsciiPanel.widthInCharacters

    override val heightInCharacters: Int
        get() = resizableAsciiPanel.heightInCharacters

    var backgroundColor: Color
        get() = resizableAsciiPanel.backgroundColor
        set(value) {
            resizableAsciiPanel.backgroundColor = value
        }

    init {
        contentPane.background = backgroundColor
        contentPane.layout = GridBagLayout()
        val gridBagConstraints = GridBagConstraints()
        gridBagConstraints.gridx = 0
        gridBagConstraints.gridy = 0
        gridBagConstraints.gridwidth = GridBagConstraints.REMAINDER
        gridBagConstraints.gridheight = GridBagConstraints.REMAINDER
        gridBagConstraints.fill = GridBagConstraints.BOTH
        gridBagConstraints.anchor = GridBagConstraints.CENTER
        gridBagConstraints.weightx = 1.0
        gridBagConstraints.weighty = 1.0

        contentPane.add(resizableAsciiPanel, gridBagConstraints)

        addKeyListener(this)
        addMouseListener(this)

        defaultCloseOperation = EXIT_ON_CLOSE

        pack()
        minimumSize = size

        isVisible = true
    }

    fun setSubpanelGroup(subpanelGroup: AsciiSubpanelGroup) {
        resizableAsciiPanel.setSubpanelGroup(subpanelGroup)
        refresh()
    }

    override fun repaintCharacters(bounds: Rectangle) {
        resizableAsciiPanel.repaintCharacters(bounds)
    }

    override fun drawCharacter(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        resizableAsciiPanel.drawCharacter(character, foregroundColor, backgroundColor, x, y)
    }

    override fun drawCharacter(characterData: AsciiCharacterData, x: Int, y: Int) {
        resizableAsciiPanel.drawCharacter(characterData, x, y)
    }

    override fun getInput(): InputEvent? = inputQueue.poll()

    override fun refresh() {
        resizableAsciiPanel.refresh()
    }

    override fun keyPressed(e: KeyEvent) {
        inputQueue.add(e)
    }

    override fun keyReleased(e: KeyEvent) {}
    override fun keyTyped(e: KeyEvent) {}
    override fun mouseClicked(e: MouseEvent) {
        inputQueue.add(e)
    }

    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
    override fun mousePressed(e: MouseEvent) {}
    override fun mouseReleased(e: MouseEvent) {}
}
